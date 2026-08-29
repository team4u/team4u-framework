package com.team4u.framework.singleflight.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.team4u.framework.kv.KvRecord;
import com.team4u.framework.kv.KvStore;
import com.team4u.framework.kv.KvStoreException;
import com.team4u.framework.kv.PutMode;
import com.team4u.framework.kv.SpaceKey;
import com.team4u.framework.kv.lock.KvLock;
import com.team4u.framework.singleflight.api.SingleFlightConflictException;
import com.team4u.framework.singleflight.api.SingleFlightExecution;
import com.team4u.framework.singleflight.api.SingleFlightExecutionException;
import com.team4u.framework.singleflight.api.SingleFlightTimeoutException;
import com.team4u.framework.singleflight.config.ContentionPolicy;
import com.team4u.framework.singleflight.config.SingleFlightRule;
import com.team4u.framework.singleflight.policy.FallbackConverter;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.util.concurrent.TimeUnit;

/**
 * 会话协调器：单个 key 上执行窗口的状态机——抢锁成为执行者、发布终态回执、
 * 或作为竞争者按策略等待 / 快速失败 / 降级收尾。
 * <p>
 * 状态全部落在协调存储的三个 space：执行权锁（{@code singleflight.lock}）、
 * 会话回执（{@code singleflight.session}）、结果缓存（{@code singleflight.cache}）。
 * 终态发布以自己的 PENDING 信封为 CAS 期望值（token fencing）：锁被接管者重新开窗后，
 * 旧执行者晚到的写入无法覆盖新会话。
 * </p>
 * <p>
 * 本类只关心「执行窗口如何流转」，不感知规则加载与配置来源——所有规则语义
 * 经 {@link CompiledRule} 注入；竞争与失败的用户侧收尾（含 errorFallback 兑底）
 * 也集中在这里，形成组件异常的单一出口。
 * </p>
 *
 * @author jay.wu
 */
@Slf4j
class SessionCoordinator {

    private final Clock clock;
    private final FallbackConverter fallbackConverter = new FallbackConverter();

    SessionCoordinator(Clock clock) {
        this.clock = clock;
    }

    /**
     * 进入带 key 的协调流程：先读结果缓存，再读会话终态，两者都未命中才抢锁。
     */
    <T> T executeWithKey(CompiledRule rule, SingleFlightExecution<T> execution, String key) {
        // 结果缓存命中：直接反序列化返回，不进入锁与会话协调
        if (rule.rule().isCacheEnabled()) {
            KvRecord cached = rule.resultStore().get(cacheKey(key));
            if (cached != null) {
                return cast(ResultCodec.decode(cached.getValue(), execution.getReturnType()));
            }
        }

        // 会话已是终态（上一执行窗口刚结束）：直接复用结果或失败，保证同 key 单次执行语义
        KvRecord record = rule.coordinationStore().get(sessionKey(key));
        if (record != null && SessionEnvelope.of(record.getValue()).isTerminal()) {
            return finishSession(rule, SessionEnvelope.of(record.getValue()), execution);
        }
        return coordinate(rule, execution, key, true);
    }

    /**
     * 抢锁协调：成为执行者则写 PENDING 会话并执行加载函数；抢锁失败按竞争策略收尾。
     *
     * @param includeCache 执行成功后是否写结果缓存；接管路径传 false，
     *                     避免由未持有原始请求语义的线程替调用方决定长 TTL 缓存
     */
    private <T> T coordinate(CompiledRule rule, SingleFlightExecution<T> execution,
                             String key, boolean includeCache) {
        KvLock lock = tryAcquire(rule, key);
        if (lock == null) {
            return onContention(rule, execution, key);
        }
        try {
            // 抢锁成功后重读会话：从进入协调到抢到锁之间，上一个 leader 可能已发布终态。
            // 直接开新执行会破坏“同 key 只执行一次”，此处复用已发布的结果。
            SessionEnvelope terminal = readTerminal(rule, key);
            if (terminal != null) {
                return finishSession(rule, terminal, execution);
            }
            SessionEnvelope pending = SessionEnvelope.pending(lock.token(), clock.millis());
            rule.coordinationStore().put(sessionKey(key), record(pending, sessionTtl(rule)),
                    PutMode.SET);
            return executeLeader(rule, execution, key, lock, pending, includeCache);
        } finally {
            release(lock);
        }
    }

    /**
     * 执行者路径：执行加载函数，无论成败都以 CAS 从自己的 PENDING 发布终态会话，
     * 可缓存的成功结果随后写入结果缓存。
     */
    private <T> T executeLeader(CompiledRule rule, SingleFlightExecution<T> execution,
                                String key, KvLock lock, SessionEnvelope pending,
                                boolean includeCache) {
        T result;
        try {
            result = execution.getLoader().load();
        } catch (Throwable throwable) {
            // 失败也发布终态：窗口内的 WAIT 调用者收到重构的失败，而不是各自重复回源
            writeTerminal(rule, key, pending, SessionEnvelope.failure(lock.token(),
                    safeMessage(throwable), clock.millis()), rule.rule().getFailureTtlMillis());
            throw unchecked(throwable);
        }

        boolean cacheable = isCacheable(rule, execution, result);
        JsonNode resultJson = ResultCodec.toJson(result, execution.getReturnType());
        writeTerminal(rule, key, pending, SessionEnvelope.success(
                lock.token(), resultJson, cacheable, clock.millis()),
                rule.rule().getUncacheableTtlMillis());
        if (includeCache && rule.rule().isCacheEnabled() && cacheable) {
            writeResultCache(rule, execution, key, resultJson);
        }
        return result;
    }

    /**
     * 结果缓存写入：写失败通常可自愈（下次执行会重写），只有 FAIL_CLOSED 才中断本次返回。
     */
    private <T> void writeResultCache(CompiledRule rule, SingleFlightExecution<T> execution,
                                      String key, JsonNode resultJson) {
        try {
            rule.resultStore().put(cacheKey(key), KvRecord.of(resultJson.toString(),
                    rule.rule().getCacheTtlMillis(), clock.millis()), PutMode.SET);
        } catch (RuntimeException e) {
            KvStoreException storeFailure = storeException(e);
            if (EffectivePolicies.storeFailure(rule.rule()) == com.team4u.framework.singleflight.config.StoreFailurePolicy.FAIL_CLOSED) {
                throw new com.team4u.framework.singleflight.api.SingleFlightConfigException(
                        "Singleflight store failure|point=" + execution.getPoint(), storeFailure);
            }
            log.warn("SessionCoordinator|cacheWriteFailurePassThrough|point={}",
                    execution.getPoint(), storeFailure);
        }
    }

    /**
     * 以自己的 PENDING 信封为期望值 CAS 发布终态会话（token fencing）：
     * 若锁已被接管者重新开窗（新 token 的 PENDING），本次 CAS 失败，旧执行者无法覆盖接管者的会话。
     *
     * @return CAS 是否成功；失败只记 warn，不影响执行者自身向调用方返回结果
     */
    private boolean writeTerminal(CompiledRule rule, String key, SessionEnvelope pending,
                                  SessionEnvelope terminal, long ttlMillis) {
        try {
            return rule.cas().compareAndSet(sessionKey(key), pending.toJson(),
                    record(terminal, ttlMillis));
        } catch (RuntimeException e) {
            log.warn("SessionCoordinator|sessionWriteFailed|key={}", key, e);
            return false;
        }
    }

    /**
     * 抢锁失败的竞争收尾：按规则竞争策略快速失败、返回降级值或进入等待 / 接管循环。
     */
    private <T> T onContention(CompiledRule rule, SingleFlightExecution<T> execution,
                               String key) {
        if (rule.rule().getContention() == ContentionPolicy.FAIL_FAST) {
            return conflictOrErrorFallback(rule, execution, key);
        }
        if (rule.rule().getContention() == ContentionPolicy.FALLBACK) {
            return cast(fallbackConverter.convert(rule.rule().getFallback(),
                    execution.getReturnType()));
        }
        return waitOrTakeOver(rule, execution, key);
    }

    /**
     * WAIT 主循环：轮询会话与锁直到终态复用、接管机会或等待超时。
     * <ul>
     *     <li>读到终态会话 → 复用结果 / 重构失败，直接返回</li>
     *     <li>PENDING 且锁记录仍存在 → 执行者还活着，休眠后继续轮询</li>
     *     <li>PENDING 且锁记录已消失 → 执行者疑似崩溃，尝试抢锁接管</li>
     *     <li>超过 waitTimeoutMillis → 抛 {@link SingleFlightTimeoutException}</li>
     * </ul>
     */
    private <T> T waitOrTakeOver(CompiledRule rule, SingleFlightExecution<T> execution,
                                 String key) {
        long deadline = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(rule.rule().getWaitTimeoutMillis());
        while (System.nanoTime() < deadline) {
            KvRecord record = rule.coordinationStore().get(sessionKey(key));
            if (record != null) {
                SessionEnvelope session = SessionEnvelope.of(record.getValue());
                if (session.isTerminal()) {
                    return finishSession(rule, session, execution);
                }
                if (rule.coordinationStore().get(lockKey(key)) != null) {
                    sleep(rule.rule().getPollIntervalMillis());
                    continue;
                }
            }
            KvLock lock = tryAcquire(rule, key);
            if (lock != null) {
                return coordinateAfterTakeover(rule, execution, key, lock);
            }
            sleep(rule.rule().getPollIntervalMillis());
        }
        return timeoutOrErrorFallback(rule, execution, key);
    }

    /**
     * 接管路径：以新 token 重开执行窗口，接管执行默认不写结果缓存。
     */
    private <T> T coordinateAfterTakeover(CompiledRule rule, SingleFlightExecution<T> execution,
                                          String key, KvLock lock) {
        try {
            // 接管前重读会话：从观察到“锁不存在”到真正抢到锁之间，原 leader 可能刚发布终态。
            // 终态已存在则直接复用，避免同 key 二次执行。
            SessionEnvelope terminal = readTerminal(rule, key);
            if (terminal != null) {
                return finishSession(rule, terminal, execution);
            }
            SessionEnvelope pending = SessionEnvelope.pending(lock.token(), clock.millis());
            rule.coordinationStore().put(sessionKey(key), record(pending, sessionTtl(rule)),
                    PutMode.SET);
            return executeLeader(rule, execution, key, lock, pending, false);
        } finally {
            release(lock);
        }
    }

    /**
     * 读到会话且为终态时返回信封，否则返回 null。
     */
    private SessionEnvelope readTerminal(CompiledRule rule, String key) {
        KvRecord record = rule.coordinationStore().get(sessionKey(key));
        if (record == null) {
            return null;
        }
        SessionEnvelope session = SessionEnvelope.of(record.getValue());
        return session.isTerminal() ? session : null;
    }

    /**
     * 消费终态会话：失败优先 errorFallback 兑底，否则重构为
     * {@link SingleFlightExecutionException}；成功把 result JSON 反序列化为返回类型。
     */
    private <T> T finishSession(CompiledRule rule, SessionEnvelope session,
                                SingleFlightExecution<T> execution) {
        if (SessionEnvelope.STATE_FAILURE.equals(session.state())) {
            if (rule.rule().getErrorFallback() != null) {
                return cast(fallbackConverter.convert(rule.rule().getErrorFallback(),
                        execution.getReturnType()));
            }
            throw new SingleFlightExecutionException("Singleflight loader failed|point="
                    + execution.getPoint() + "|error=" + session.errorMessage());
        }
        return cast(ResultCodec.decode(session.result().toString(), execution.getReturnType()));
    }

    /**
     * 组件异常的兑底出口：配置了 errorFallback 时把异常转为返回值，否则原样抛出。
     * 仅覆盖竞争 / 超时 / 失败会话重构三类用户侧异常，不覆盖配置错误。
     */
    private <T> T conflictOrErrorFallback(CompiledRule rule, SingleFlightExecution<T> execution,
                                          String key) {
        if (rule.rule().getErrorFallback() != null) {
            return cast(fallbackConverter.convert(rule.rule().getErrorFallback(),
                    execution.getReturnType()));
        }
        throw new SingleFlightConflictException("Singleflight conflict|point="
                + execution.getPoint() + "|key=" + key);
    }

    private <T> T timeoutOrErrorFallback(CompiledRule rule, SingleFlightExecution<T> execution,
                                          String key) {
        if (rule.rule().getErrorFallback() != null) {
            return cast(fallbackConverter.convert(rule.rule().getErrorFallback(),
                    execution.getReturnType()));
        }
        throw new SingleFlightTimeoutException("Singleflight wait timeout|point="
                + execution.getPoint() + "|key=" + key
                + "|timeoutMillis=" + rule.rule().getWaitTimeoutMillis());
    }

    /**
     * 尝试获取执行权互斥锁，锁存储故障统一转 {@link KvStoreException} 交给上层策略处置。
     */
    private KvLock tryAcquire(CompiledRule rule, String key) {
        try {
            return rule.lockManager().tryAcquire(key, rule.rule().getLockLeaseMillis());
        } catch (RuntimeException e) {
            throw storeException(e);
        }
    }

    /**
     * 判断结果是否可缓存：未配置 cacheWhen 默认可缓存；
     * 配置时以加载结果为匹配对象、参数名 Map 为属性上下文执行 Criterion 匹配。
     */
    private boolean isCacheable(CompiledRule rule, SingleFlightExecution<?> execution,
                                Object result) {
        if (rule.cacheWhen() == null) {
            return true;
        }
        return rule.cacheWhen().matches(EffectivePolicies.resultContext(result, execution));
    }

    /**
     * PENDING 会话 TTL：至少覆盖“最晚的等待者等到超时 + 最长的终态存活窗口”，
     * 保证等待者在超时前读到的 PENDING 不会被 TTL 提前清除；下限 1 秒兜底极小配置。
     */
    private static long sessionTtl(CompiledRule rule) {
        return Math.max(rule.rule().getWaitTimeoutMillis()
                + Math.max(rule.rule().getUncacheableTtlMillis(), rule.rule().getFailureTtlMillis()),
                1000L);
    }

    /**
     * 释放执行权锁：释放失败只记 warn，锁最终会因租约到期而自动放行接管。
     */
    private static void release(KvLock lock) {
        try {
            lock.close();
        } catch (RuntimeException e) {
            log.warn("SessionCoordinator|lockReleaseFailed|lock={}", lock.name(), e);
        }
    }

    /**
     * 轮询休眠；被中断时恢复中断标记并按等待超时收尾。
     */
    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SingleFlightTimeoutException("Singleflight wait interrupted");
        }
    }

    private static SpaceKey lockKey(String key) {
        return SpaceKey.of(SingleFlightEngine.LOCK_SPACE, key);
    }

    private static SpaceKey sessionKey(String key) {
        return SpaceKey.of(SingleFlightEngine.SESSION_SPACE, key);
    }

    private static SpaceKey cacheKey(String key) {
        return SpaceKey.of(SingleFlightEngine.CACHE_SPACE, key);
    }

    private KvRecord record(SessionEnvelope envelope, long ttlMillis) {
        return KvRecord.of(envelope.toJson(), ttlMillis, clock.millis());
    }

    private static String safeMessage(Throwable throwable) {
        return throwable.getMessage() == null ? throwable.getClass().getName() : throwable.getMessage();
    }

    @SuppressWarnings("unchecked")
    private static <T> T cast(Object value) {
        return (T) value;
    }

    /**
     * 业务异常转运行时异常原样抛出：RuntimeException / Error 直接上抛，
     * 其余受检异常包为 IllegalStateException，保证加载函数的原始异常不被吞掉。
     */
    private static RuntimeException unchecked(Throwable throwable) {
        if (throwable instanceof RuntimeException) {
            return (RuntimeException) throwable;
        }
        if (throwable instanceof Error) {
            throw (Error) throwable;
        }
        throw new IllegalStateException(throwable);
    }

    /**
     * 非 KvStoreException 的存储运行时异常统一包装，便于上层按存储故障策略裁决。
     */
    private static KvStoreException storeException(RuntimeException e) {
        if (e instanceof KvStoreException) {
            return (KvStoreException) e;
        }
        return new KvStoreException(e.getMessage(), e);
    }
}
