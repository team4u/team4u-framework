package com.team4u.framework.singleflight.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.team4u.framework.base.util.TextTemplate;
import com.team4u.framework.config.core.ConfigManager;
import com.team4u.framework.config.core.support.ConfigDrivenRegistry;
import com.team4u.framework.criterion.MatchContext;
import com.team4u.framework.kv.CasCapable;
import com.team4u.framework.kv.KvRecord;
import com.team4u.framework.kv.KvStore;
import com.team4u.framework.kv.KvStoreException;
import com.team4u.framework.kv.KvStores;
import com.team4u.framework.kv.PutMode;
import com.team4u.framework.kv.SpaceKey;
import com.team4u.framework.kv.lock.KvLock;
import com.team4u.framework.kv.lock.KvLockManager;
import com.team4u.framework.serializer.json.JsonUtil;
import com.team4u.framework.singleflight.api.SingleFlightConfigException;
import com.team4u.framework.singleflight.api.SingleFlightConflictException;
import com.team4u.framework.singleflight.api.SingleFlightExecution;
import com.team4u.framework.singleflight.api.SingleFlightExecutionException;
import com.team4u.framework.singleflight.api.SingleFlightTimeoutException;
import com.team4u.framework.singleflight.config.ContentionPolicy;
import com.team4u.framework.singleflight.config.InvalidKeyPolicy;
import com.team4u.framework.singleflight.config.RuleMissingPolicy;
import com.team4u.framework.singleflight.config.SingleFlightRule;
import com.team4u.framework.singleflight.config.StoreFailurePolicy;
import com.team4u.framework.singleflight.policy.FallbackConverter;
import com.team4u.framework.singleflight.policy.KeyResolver;
import com.team4u.framework.singleflight.policy.SingleFlightCondition;
import com.team4u.framework.singleflight.store.SingleFlightStores;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 回源合并（singleflight）协调引擎：同一 key 的并发调用只允许一个执行者真正回源，
 * 其余调用者按规则竞争策略（{@link ContentionPolicy}）以等待复用、快速失败或降级值收尾。
 * <p>
 * 协调状态全部落在底层 {@link KvStore} 的三个 space 中，跨线程、跨实例共享同一执行窗口：
 * </p>
 * <ul>
 *     <li><b>{@code singleflight.lock}</b>：执行权互斥锁（{@link KvLockManager}），
 *     带租约与后台续约；持有者崩溃后租约到期，等待者可接管</li>
 *     <li><b>{@code singleflight.session}</b>：会话信封（{@link SessionEnvelope}），
 *     PENDING → 终态（成功 / 失败）的状态机，终态经 CAS 发布实现 token fencing</li>
 *     <li><b>{@code singleflight.cache}</b>：结果缓存（可选），命中后调用者不再进入协调流程</li>
 * </ul>
 * <p>
 * 一次执行的裁决顺序：规则加载（配置键 {@code team4u.singleflight.{point}}）→ 请求校验 →
 * skipWhen 跳过判断 → key 渲染 → 结果缓存读取 → 锁协调（抢锁成为执行者，或按竞争策略收尾）。
 * 存储故障按规则的 {@code onStoreFailure} 策略处置，默认值随竞争策略推导。
 * </p>
 *
 * @author jay.wu
 */
@Slf4j
public class SingleFlightEngine implements AutoCloseable {

    /**
     * 规则配置模式：配置键 team4u.singleflight.{point}，一个 point 对应一条 JSON 规则
     */
    public static final String DEFAULT_CONFIG_PATTERN = "team4u.singleflight.*";

    /**
     * 全局规则缺失策略配置键（规则 JSON 内的同名字段不参与裁决，只有此全局键生效）
     */
    public static final String GLOBAL_RULE_MISSING_KEY = "team4u.singleflight.on_rule_missing";

    /**
     * 执行权互斥锁 space
     */
    public static final String LOCK_SPACE = "singleflight.lock";

    /**
     * 会话信封 space
     */
    public static final String SESSION_SPACE = "singleflight.session";

    /**
     * 结果缓存 space
     */
    public static final String CACHE_SPACE = "singleflight.cache";

    private final ConfigManager configManager;
    /**
     * 默认存储（已解包到最内层真实存储），规则未声明 store 时使用
     */
    private final KvStore defaultStore;
    /**
     * 默认协调存储：与 defaultStore 指向同一底层存储，仅以 KvStore 视角访问（锁 / 会话 / 缓存读写）
     */
    private final KvStore defaultCoordinationStore;
    /**
     * 默认存储的 CAS 能力视图，终态会话发布（token fencing）依赖它
     */
    private final CasCapable defaultCas;
    private final Clock clock;
    private final FallbackConverter fallbackConverter = new FallbackConverter();
    /**
     * 规则注册表：配置驱动加载 + 热更新安全替换（新规则编译成功才替换旧规则）
     */
    private final ConfigDrivenRegistry<CompiledRule> rules;
    private final Map<String, KeyResolver> templates = new ConcurrentHashMap<>();
    /**
     * 锁管理器缓存：按存储名共享同一把 KvLockManager，避免同存储重复创建续约资源
     */
    private final ConcurrentHashMap<String, KvLockManager> lockManagers = new ConcurrentHashMap<>();
    /**
     * point → 存储名登记表，用于在规则热更新时检测并拒绝存储热切换
     */
    private final Map<String, String> activeStores = new ConcurrentHashMap<>();

    public SingleFlightEngine(ConfigManager configManager, KvStore defaultStore) {
        this(configManager, defaultStore, Clock.systemUTC());
    }

    public SingleFlightEngine(ConfigManager configManager, KvStore defaultStore, Clock clock) {
        this.configManager = Objects.requireNonNull(configManager, "configManager");
        // 解包装饰链直达底层存储：协调与缓存路径不经过 TieredStore / ObservedStore 等装饰层，
        // 避免本地缓存喂出陈旧的锁与会话
        this.defaultStore = unwrapStore(Objects.requireNonNull(defaultStore, "defaultStore"));
        this.defaultCas = requireCas(this.defaultStore);
        this.defaultCoordinationStore = (KvStore) this.defaultCas;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.rules = new ConfigDrivenRegistry<>(configManager, DEFAULT_CONFIG_PATTERN,
                this::compileRule);
    }

    /**
     * 执行一次回源合并请求：完成规则裁决、key 渲染、缓存读取与锁协调的全流程。
     *
     * @param execution 执行请求（point、参数上下文、返回类型与加载函数）
     * @param <T>       加载函数返回类型
     * @return 本地或复用（缓存 / 等待 / 降级）的执行结果
     * @throws SingleFlightConfigException      point 为空、规则 id 与 point 不一致、规则缺失（ERROR 策略）等配置问题
     * @throws SingleFlightConflictException    竞争策略为 FAIL_FAST 且锁被他人持有
     * @throws SingleFlightTimeoutException     WAIT 等待终态或接管机会超时
     * @throws SingleFlightExecutionException   复用到其他执行者发布的失败会话
     */
    public <T> T execute(SingleFlightExecution<T> execution) {
        if (execution.getPoint() == null || execution.getPoint().trim().isEmpty()) {
            throw new SingleFlightConfigException("Singleflight point must not be empty");
        }
        CompiledRule rule = rules.get(execution.getPoint());
        if (rule == null) {
            return onRuleMissing(execution);
        }
        // id 必须与 point 完全一致，防止配置键与规则体错位导致跨 point 误合并
        if (!execution.getPoint().equals(rule.rule.getId())) {
            throw new SingleFlightConfigException("Singleflight rule id must match point"
                    + "|point=" + execution.getPoint() + "|id=" + rule.rule.getId());
        }
        // enabled=false：完全绕过协调与缓存，直接执行加载函数
        if (!rule.rule.isEnabled()) {
            return load(execution);
        }
        // 提前校验 skipWhen 变量可解析性，让配置错误在执行期第一时间暴露
        validateCriterionVariables(rule.skipWhen, execution, "skipWhen");
        validateRuleForExecution(rule.rule, execution);
        if (rule.skipWhen != null && matchesSkip(rule, execution)) {
            return load(execution);
        }

        String key = renderKey(rule, execution);
        // key 渲染失败且策略为 PASS_THROUGH：不做协调，直接执行加载函数
        if (key == null) {
            return load(execution);
        }
        try {
            return executeWithKey(rule, execution, key);
        } catch (KvStoreException e) {
            return onStoreFailure(rule, execution, e);
        }
    }

    /**
     * 规则缺失时的收尾：ERROR 抛配置异常，PASS_THROUGH 记 warn 后直接执行加载函数。
     */
    private <T> T onRuleMissing(SingleFlightExecution<T> execution) {
        RuleMissingPolicy policy = globalRuleMissingPolicy();
        if (policy == RuleMissingPolicy.ERROR) {
            throw new SingleFlightConfigException(
                    "Singleflight rule is missing|point=" + execution.getPoint());
        }
        log.warn("SingleflightEngine|ruleMissingPassThrough|point={}", execution.getPoint());
        return load(execution);
    }

    /**
     * 读取全局规则缺失策略配置；缺省 PASS_THROUGH，取值不合法视为配置错误。
     */
    private RuleMissingPolicy globalRuleMissingPolicy() {
        String value = configManager.getString(GLOBAL_RULE_MISSING_KEY).orElse(null);
        if (value == null || value.trim().isEmpty()) {
            return RuleMissingPolicy.PASS_THROUGH;
        }
        try {
            return RuleMissingPolicy.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new SingleFlightConfigException(
                    "Invalid onRuleMissing policy|value=" + value, e);
        }
    }

    /**
     * 进入带 key 的协调流程：先读结果缓存，再读会话终态，两者都未命中才抢锁。
     */
    private <T> T executeWithKey(CompiledRule rule, SingleFlightExecution<T> execution,
                                 String key) {
        // 结果缓存命中：直接反序列化返回，不进入锁与会话协调
        if (rule.rule.isCacheEnabled()) {
            KvRecord cached = rule.resultStore.get(cacheKey(key));
            if (cached != null) {
                return cast(decode(cached.getValue(), execution.getReturnType()));
            }
        }

        // 会话已是终态（上一执行窗口刚结束）：直接复用结果或失败，保证同 key 单次执行语义
        KvRecord record = rule.coordinationStore.get(sessionKey(key));
        if (record != null && SessionEnvelope.of(record.getValue()).isTerminal()) {
            return finishSession(SessionEnvelope.of(record.getValue()), execution);
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
            KvRecord record = rule.coordinationStore.get(sessionKey(key));
            if (record != null) {
                SessionEnvelope session = SessionEnvelope.of(record.getValue());
                if (session.isTerminal()) {
                    return finishSession(session, execution);
                }
            }
            SessionEnvelope pending = SessionEnvelope.pending(lock.token(), clock.millis());
            rule.coordinationStore.put(sessionKey(key), record(pending, sessionTtl(rule)),
                    PutMode.SET);
            return executeLeader(rule, execution, key, lock, pending, includeCache);
        } finally {
            release(lock);
        }
    }

    /**
     * 尝试获取执行权互斥锁，锁存储故障统一转 {@link KvStoreException} 交给上层策略处置。
     */
    private KvLock tryAcquire(CompiledRule rule, String key) {
        try {
            return rule.lockManager.tryAcquire(key, rule.rule.getLockLeaseMillis());
        } catch (RuntimeException e) {
            throw storeException(e);
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
            result = load(execution);
        } catch (Throwable throwable) {
            // 失败也发布终态：窗口内的 WAIT 调用者收到重构的失败，而不是各自重复回源
            writeTerminal(rule, key, pending, SessionEnvelope.failure(lock.token(),
                    safeMessage(throwable), clock.millis()), rule.rule.getFailureTtlMillis());
            throw unchecked(throwable);
        }

        boolean cacheable = isCacheable(rule, execution, result);
        JsonNode resultJson = toJson(result, execution.getReturnType());
        writeTerminal(rule, key, pending, SessionEnvelope.success(
                lock.token(), resultJson, cacheable, clock.millis()),
                rule.rule.getUncacheableTtlMillis());
        if (includeCache && rule.rule.isCacheEnabled() && cacheable) {
            try {
                rule.resultStore.put(cacheKey(key), KvRecord.of(resultJson.toString(),
                        rule.rule.getCacheTtlMillis(), clock.millis()), PutMode.SET);
            } catch (RuntimeException e) {
                // 结果缓存写失败通常可自愈（下次执行会重写），只有 FAIL_CLOSED 才中断本次返回
                KvStoreException storeFailure = storeException(e);
                if (effectiveStoreFailurePolicy(rule.rule) == StoreFailurePolicy.FAIL_CLOSED) {
                    throw new SingleFlightConfigException("Singleflight store failure|point="
                            + execution.getPoint(), storeFailure);
                }
                log.warn("SingleflightEngine|cacheWriteFailurePassThrough|point={}",
                        execution.getPoint(), storeFailure);
            }
        }
        return result;
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
            return rule.cas.compareAndSet(sessionKey(key), pending.toJson(),
                    record(terminal, ttlMillis));
        } catch (RuntimeException e) {
            log.warn("SingleflightEngine|sessionWriteFailed|key={}", key, e);
            return false;
        }
    }

    /**
     * 抢锁失败的竞争收尾：按规则竞争策略快速失败、返回降级值或进入等待 / 接管循环。
     */
    private <T> T onContention(CompiledRule rule, SingleFlightExecution<T> execution,
                               String key) {
        if (rule.rule.getContention() == ContentionPolicy.FAIL_FAST) {
            throw new SingleFlightConflictException("Singleflight conflict|point="
                    + execution.getPoint() + "|key=" + key);
        }
        if (rule.rule.getContention() == ContentionPolicy.FALLBACK) {
            return cast(fallbackConverter.convert(rule.rule.getFallback(),
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
    @SuppressWarnings("unchecked")
    private <T> T waitOrTakeOver(CompiledRule rule, SingleFlightExecution<T> execution,
                                 String key) {
        long deadline = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(rule.rule.getWaitTimeoutMillis());
        while (System.nanoTime() < deadline) {
            KvRecord record = rule.coordinationStore.get(sessionKey(key));
            if (record != null) {
                SessionEnvelope session = SessionEnvelope.of(record.getValue());
                if (session.isTerminal()) {
                    return finishSession(session, execution);
                }
                if (rule.coordinationStore.get(lockKey(key)) != null) {
                    sleep(rule.rule.getPollIntervalMillis());
                    continue;
                }
            }
            KvLock lock = tryAcquire(rule, key);
            if (lock != null) {
                return coordinateAfterTakeover(rule, execution, key, lock);
            }
            sleep(rule.rule.getPollIntervalMillis());
        }
        throw new SingleFlightTimeoutException("Singleflight wait timeout|point="
                + execution.getPoint() + "|key=" + key
                + "|timeoutMillis=" + rule.rule.getWaitTimeoutMillis());
    }

    /**
     * 接管路径：以新 token 重开执行窗口，接管执行默认不写结果缓存。
     */
    private <T> T coordinateAfterTakeover(CompiledRule rule, SingleFlightExecution<T> execution,
                                          String key, KvLock lock) {
        try {
            // 接管前重读会话：从观察到“锁不存在”到真正抢到锁之间，原 leader 可能刚发布终态。
            // 终态已存在则直接复用，避免同 key 二次执行。
            KvRecord record = rule.coordinationStore.get(sessionKey(key));
            if (record != null) {
                SessionEnvelope session = SessionEnvelope.of(record.getValue());
                if (session.isTerminal()) {
                    return finishSession(session, execution);
                }
            }
            SessionEnvelope pending = SessionEnvelope.pending(lock.token(), clock.millis());
            rule.coordinationStore.put(sessionKey(key), record(pending, sessionTtl(rule)),
                    PutMode.SET);
            return executeLeader(rule, execution, key, lock, pending, false);
        } finally {
            release(lock);
        }
    }

    /**
     * 消费终态会话：失败重构为 {@link SingleFlightExecutionException}，
     * 成功把 result JSON 反序列化为执行请求的返回类型。
     */
    @SuppressWarnings("unchecked")
    private <T> T finishSession(SessionEnvelope session, SingleFlightExecution<T> execution) {
        if (SessionEnvelope.STATE_FAILURE.equals(session.state())) {
            throw new SingleFlightExecutionException("Singleflight loader failed|point="
                    + execution.getPoint() + "|error=" + session.errorMessage());
        }
        return (T) decode(session.result().toString(), execution.getReturnType());
    }

    /**
     * 协调存储故障收尾：FAIL_CLOSED 立即失败，PASS_THROUGH 记 warn 后直接执行加载函数。
     */
    private <T> T onStoreFailure(CompiledRule rule, SingleFlightExecution<T> execution,
                                 KvStoreException e) {
        if (effectiveStoreFailurePolicy(rule.rule) == StoreFailurePolicy.FAIL_CLOSED) {
            throw new SingleFlightConfigException("Singleflight store failure|point="
                    + execution.getPoint(), e);
        }
        log.warn("SingleflightEngine|storeFailurePassThrough|point={}",
                execution.getPoint(), e);
        return load(execution);
    }

    /**
     * 计算生效的存储故障策略：规则显式配置优先；
     * 省略时 FAIL_FAST（本身即拒绝语义）默认 FAIL_CLOSED，WAIT / FALLBACK 默认 PASS_THROUGH。
     */
    private static StoreFailurePolicy effectiveStoreFailurePolicy(SingleFlightRule rule) {
        if (rule.getOnStoreFailure() != null) {
            return rule.getOnStoreFailure();
        }
        return rule.getContention() == ContentionPolicy.FAIL_FAST
                ? StoreFailurePolicy.FAIL_CLOSED : StoreFailurePolicy.PASS_THROUGH;
    }

    /**
     * 判断结果是否可缓存：未配置 cacheWhen 默认可缓存；
     * 配置时以加载结果为匹配对象、参数名 Map 为属性上下文执行 Criterion 匹配。
     */
    private boolean isCacheable(CompiledRule rule, SingleFlightExecution<?> execution,
                                Object result) {
        if (rule.cacheWhen == null) {
            return true;
        }
        MatchContext context = MatchContext.of(result);
        context.setAttributes(execution.getArguments());
        return rule.cacheWhen.matches(context);
    }

    /**
     * 判断是否命中跳过条件：以参数名 Map 为匹配对象与属性上下文执行 skipWhen 匹配，
     * 命中则完全绕过协调与缓存。
     */
    private boolean matchesSkip(CompiledRule rule, SingleFlightExecution<?> execution) {
        MatchContext context = MatchContext.of(execution.getArguments());
        context.setAttributes(execution.getArguments());
        return rule.skipWhen.matches(context);
    }

    /**
     * 渲染最终协调 key：未配置模板时以 point 为业务 key（同 point 全局共享窗口）；
     * 渲染结果为 null 时按 onInvalidKey 策略处置（PASS_THROUGH 返回 null 由调用方直接回源）；
     * 最终经 {@link SingleFlightKeys} 完成 point 拼接、编码与摘要。
     */
    private String renderKey(CompiledRule rule, SingleFlightExecution<?> execution) {
        String rendered;
        if (rule.keyResolver == null) {
            rendered = execution.getPoint();
        } else {
            rendered = rule.keyResolver.render(execution.getArguments());
        }
        if (rendered == null) {
            if (rule.rule.getOnInvalidKey() == InvalidKeyPolicy.PASS_THROUGH) {
                return null;
            }
            throw new SingleFlightConfigException("Singleflight rendered key is invalid"
                    + "|point=" + execution.getPoint() + "|template=" + rule.rule.getKey());
        }
        return SingleFlightKeys.compose(execution.getPoint(), rendered,
                rule.rule.getDigestThreshold());
    }

    /**
     * 直接执行加载函数（跳过 / 缓存 / 直通路径共用），业务异常原样抛出。
     */
    private <T> T load(SingleFlightExecution<T> execution) {
        try {
            return execution.getLoader().load();
        } catch (Throwable throwable) {
            throw unchecked(throwable);
        }
    }

    /**
     * 结果序列化为 JSON 树：null 与 void 归一化为 JSON null；
     * 不支持 JSON 序列化的类型视为配置错误。
     */
    private static JsonNode toJson(Object result, Type returnType) {
        if (result == null || void.class.equals(returnType) || Void.TYPE.equals(returnType)) {
            return NullNode.getInstance();
        }
        Object parsed = JsonUtil.parseObj(JsonUtil.toJsonStr(result));
        if (parsed instanceof JsonNode) {
            return (JsonNode) parsed;
        }
        throw new SingleFlightConfigException(
                "Singleflight result is not JSON-serializable|type=" + result.getClass().getName());
    }

    /**
     * 结果 JSON 反序列化为目标返回类型；void 返回类型恒为 null。
     */
    private Object decode(String json, Type returnType) {
        if (void.class.equals(returnType) || Void.TYPE.equals(returnType)) {
            return null;
        }
        return JsonUtil.toBean(json, returnType);
    }

    /**
     * 编译一条规则为 {@link CompiledRule}：反序列化 → 字段校验 → 存储解析与 CAS 能力校验 →
     * 锁管理器复用 → skipWhen / cacheWhen / key 模板预编译。
     * <p>
     * 编译期完成全部可静态判定的检查，语法与结构错误在旧规则被替换前暴露；
     * 编译中途失败会回滚存储登记，保证 activeStores 与实际生效规则一致。
     * </p>
     */
    private CompiledRule compileRule(String json) {
        SingleFlightRule rule;
        try {
            rule = JsonUtil.toBean(json, SingleFlightRule.class);
        } catch (RuntimeException e) {
            throw new SingleFlightConfigException("Invalid singleflight rule json|json=" + json, e);
        }
        if (rule == null) {
            throw new SingleFlightConfigException("Singleflight rule must not be empty");
        }
        validateRule(rule);
        if (blank(rule.getId())) {
            throw new SingleFlightConfigException("Singleflight rule id must not be empty");
        }

        // 登记存储名并检测热切换：协调状态（锁/会话/缓存）落在具体存储上，
        // 运行中换存储会导致新旧窗口分裂（两边各有一个“唯一执行者”），因此拒绝热切换
        String storeName = normalizedStoreName(rule.getStore());
        String previousStore = activeStores.putIfAbsent(rule.getId(), storeName);
        if (previousStore != null && !previousStore.equals(storeName)) {
            throw new SingleFlightConfigException("Singleflight store hot-switch is not allowed"
                    + "|point=" + rule.getId() + "|old=" + previousStore + "|new=" + storeName);
        }

        KvStore rawStore = resolveStore(storeName);
        CasCapable cas = requireCas(rawStore);
        KvStore coordinationStore = (KvStore) cas;
        // 锁管理器按存储共享：同存储的多个规则复用同一续约资源，由引擎统一关闭
        KvLockManager lockManager = lockManagers.computeIfAbsent(storeKey(storeName),
                ignored -> new KvLockManager(coordinationStore, clock,
                        new KvLockManager.Config().setSpace(LOCK_SPACE)));
        try {
            SingleFlightCondition skipWhen = compileCondition(rule.getSkipWhen());
            SingleFlightCondition cacheWhen = compileCondition(rule.getCacheWhen());
            KeyResolver keyResolver = rule.getKey() == null || rule.getKey().trim().isEmpty()
                    ? null : new KeyResolver(rule.getKey());
            return new CompiledRule(rule, skipWhen, cacheWhen, keyResolver,
                    rawStore, coordinationStore, cas, lockManager);
        } catch (RuntimeException e) {
            // 编译失败回滚登记，避免留下“占住存储名却无生效规则”的脏登记
            activeStores.remove(rule.getId(), storeName);
            throw e;
        }
    }

    /**
     * 规则静态校验：时间参数必须为正、缓存开关联动 cacheTtlMillis、FALLBACK 必须携带降级 JSON。
     */
    private static void validateRule(SingleFlightRule rule) {
        positive(rule.getLockLeaseMillis(), "lockLeaseMillis");
        positive(rule.getWaitTimeoutMillis(), "waitTimeoutMillis");
        positive(rule.getPollIntervalMillis(), "pollIntervalMillis");
        positive(rule.getUncacheableTtlMillis(), "uncacheableTtlMillis");
        positive(rule.getFailureTtlMillis(), "failureTtlMillis");
        positive(rule.getDigestThreshold(), "digestThreshold");
        if (rule.isCacheEnabled() && rule.getCacheTtlMillis() <= 0) {
            throw new SingleFlightConfigException(
                    "cacheTtlMillis must be > 0 when cacheEnabled is true");
        }
        if (!rule.isCacheEnabled() && rule.getCacheTtlMillis() != 0) {
            throw new SingleFlightConfigException(
                    "cacheTtlMillis is not allowed when cacheEnabled is false");
        }
        if (rule.getContention() == ContentionPolicy.FALLBACK && rule.getFallback() == null) {
            throw new SingleFlightConfigException("FALLBACK requires fallback json");
        }
    }

    /**
     * 执行期组合校验：void 方法没有可传递的结果（禁止缓存与非 FAIL_FAST 竞争）；
     * 基本类型返回值无法承载显式 null 降级。
     */
    private static void validateRuleForExecution(SingleFlightRule rule,
                                                 SingleFlightExecution<?> execution) {
        boolean voidReturn = void.class.equals(execution.getReturnType())
                || Void.TYPE.equals(execution.getReturnType());
        if (voidReturn && (rule.isCacheEnabled()
                || rule.getContention() != ContentionPolicy.FAIL_FAST)) {
            throw new SingleFlightConfigException(
                    "void method requires cacheEnabled=false and contention=FAIL_FAST");
        }
        if (rule.getContention() == ContentionPolicy.FALLBACK && rule.getFallback() != null
                && rule.getFallback().isNull() && isPrimitive(execution.getReturnType())) {
            throw new SingleFlightConfigException(
                    "Primitive return type does not allow explicit null fallback|returnType="
                            + execution.getReturnType().getTypeName());
        }
    }

    private static boolean isPrimitive(Type type) {
        return type instanceof Class && ((Class<?>) type).isPrimitive();
    }

    /**
     * 校验条件表达式中的变量在执行上下文中可解析（要求调用方提供参数名集合，
     * 如代理边界通过 -parameters 拿到的真实方法参数名），让配置笔误尽早失败。
     */
    private void validateCriterionVariables(SingleFlightCondition condition,
                                            SingleFlightExecution<?> execution,
                                            String field) {
        if (condition == null) {
            return;
        }
        if (!execution.getParameterNames().isEmpty() && "skipWhen".equals(field)) {
            validateVariables(condition, execution.getParameterNames(), field);
        }
    }

    private static void validateVariables(SingleFlightCondition condition,
                                          Set<String> knownNames, String field) {
        for (String name : condition.variableNames()) {
            String variableName = name.startsWith("$") ? name.substring(1) : name;
            if (!knownNames.contains(variableName)) {
                throw new SingleFlightConfigException("Singleflight variable is not resolvable"
                        + "|field=" + field + "|variable=" + name);
            }
        }
    }

    /**
     * 编译条件表达式：空白返回 null（未配置），语法在规则加载期即校验。
     */
    private static SingleFlightCondition compileCondition(String expression) {
        if (expression == null || expression.trim().isEmpty()) {
            return null;
        }
        return SingleFlightCondition.compile(expression);
    }

    /**
     * 存储名归一化：null / 空白统一为空串，表示引擎默认存储。
     */
    private static String normalizedStoreName(String store) {
        return store == null || store.trim().isEmpty() ? "" : store.trim();
    }

    /**
     * 锁管理器缓存的键：默认存储使用固定占位名，与命名存储区分。
     */
    private static String storeKey(String storeName) {
        return storeName.isEmpty() ? "<default>" : storeName;
    }

    /**
     * 按名解析命名存储（直达最内层真实存储）；未注册视为配置错误。
     */
    private KvStore resolveStore(String storeName) {
        if (storeName.isEmpty()) {
            return defaultCoordinationStore;
        }
        try {
            return unwrapStore(SingleFlightStores.global().resolve(storeName));
        } catch (IllegalArgumentException e) {
            throw new SingleFlightConfigException(
                    "Singleflight store not registered|store=" + storeName, e);
        }
    }

    /**
     * 解包装饰链直达最内层真实存储。
     */
    private static KvStore unwrapStore(KvStore store) {
        return KvStores.innermost(store);
    }

    /**
     * 提取存储的 CAS 能力视图，无 CAS 能力的存储无法保证 token fencing，直接拒绝。
     */
    private static CasCapable requireCas(KvStore store) {
        CasCapable cas = KvStores.capabilityOf(store, CasCapable.class);
        if (cas == null) {
            throw new SingleFlightConfigException(
                    "Singleflight store requires CasCapable|store=" + store.getClass().getName());
        }
        return cas;
    }

    private static void positive(long value, String name) {
        if (value <= 0) {
            throw new SingleFlightConfigException(name + " must be > 0");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static SpaceKey lockKey(String key) {
        return SpaceKey.of(LOCK_SPACE, key);
    }

    private static SpaceKey sessionKey(String key) {
        return SpaceKey.of(SESSION_SPACE, key);
    }

    private static SpaceKey cacheKey(String key) {
        return SpaceKey.of(CACHE_SPACE, key);
    }

    private KvRecord record(SessionEnvelope envelope, long ttlMillis) {
        return KvRecord.of(envelope.toJson(), ttlMillis, clock.millis());
    }

    /**
     * PENDING 会话 TTL：至少覆盖“最晚的等待者等到超时 + 最长的终态存活窗口”，
     * 保证等待者在超时前读到的 PENDING 不会被 TTL 提前清除；下限 1 秒兜底极小配置。
     */
    private static long sessionTtl(CompiledRule rule) {
        return Math.max(rule.rule.getWaitTimeoutMillis()
                + Math.max(rule.rule.getUncacheableTtlMillis(), rule.rule.getFailureTtlMillis()),
                1000L);
    }

    /**
     * 释放执行权锁：释放失败只记 warn，锁最终会因租约到期而自动放行接管。
     */
    private static void release(KvLock lock) {
        try {
            lock.close();
        } catch (RuntimeException e) {
            log.warn("SingleflightEngine|lockReleaseFailed|lock={}", lock.name(), e);
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
    @SuppressWarnings("unchecked")
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

    /**
     * 释放引擎资源：注销规则监听、关闭全部锁管理器、清空缓存状态；可重复调用。
     */
    @Override
    public void close() {
        rules.destroy();
        lockManagers.values().forEach(manager -> {
            try {
                manager.close();
            } catch (RuntimeException e) {
                log.warn("Singleflight lock manager close failed", e);
            }
        });
        lockManagers.clear();
        templates.clear();
        activeStores.clear();
    }

    public void destroy() {
        close();
    }

    /**
     * 编译后的规则及其运行期资源（预解析的条件、键模板、存储视图与锁管理器）。
     * <p>
     * {@link ConfigDrivenRegistry} 保证：仅在新实例构建成功后才关闭被替换的旧实例，
     * 规则热更新期间旧资源始终可用。
     * </p>
     */
    private static final class CompiledRule implements AutoCloseable {

        private final SingleFlightRule rule;
        private final SingleFlightCondition skipWhen;
        private final SingleFlightCondition cacheWhen;
        private final KeyResolver keyResolver;
        private final KvStore resultStore;
        private final KvStore coordinationStore;
        private final CasCapable cas;
        private final KvLockManager lockManager;

        private CompiledRule(SingleFlightRule rule, SingleFlightCondition skipWhen,
                             SingleFlightCondition cacheWhen, KeyResolver keyResolver,
                             KvStore resultStore, KvStore coordinationStore,
                             CasCapable cas, KvLockManager lockManager) {
            this.rule = rule;
            this.skipWhen = skipWhen;
            this.cacheWhen = cacheWhen;
            this.keyResolver = keyResolver;
            this.resultStore = resultStore;
            this.coordinationStore = coordinationStore;
            this.cas = cas;
            this.lockManager = lockManager;
        }

        @Override
        public void close() {
            // KvLockManager 按命名存储共享，仅由引擎在 close 时统一关闭
        }
    }
}
