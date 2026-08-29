package com.team4u.framework.singleflight.core;

import com.team4u.framework.kv.CasCapable;
import com.team4u.framework.kv.KvStore;
import com.team4u.framework.kv.KvStores;
import com.team4u.framework.kv.lock.KvLockManager;
import com.team4u.framework.serializer.json.JsonUtil;
import com.team4u.framework.singleflight.api.SingleFlightConfigException;
import com.team4u.framework.singleflight.config.ContentionPolicy;
import com.team4u.framework.singleflight.config.SingleFlightRule;
import com.team4u.framework.singleflight.policy.KeyResolver;
import com.team4u.framework.singleflight.policy.SingleFlightCondition;
import com.team4u.framework.singleflight.store.SingleFlightStores;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 规则编译器：把规则 JSON 变为可直接执行的 {@link CompiledRule}。
 * <p>
 * 编译链：反序列化 → 静态校验 → 存储解析与 CAS 能力校验（拒绝热切换）→
 * 锁管理器复用 → skipWhen / cacheWhen / key 模板预编译。全部可静态判定的检查
 * 都在编译期完成，语法与结构错误在旧规则被替换前暴露。
 * </p>
 * <p>
 * 编译器持有两类跨规则共享的状态：按存储名共享的 {@link KvLockManager} 缓存
 * （避免同存储重复创建续约资源）与 point → 存储名登记表（检测并拒绝存储热切换）。
 * 引擎关闭时经 {@link #close()} 统一释放。
 * </p>
 *
 * @author jay.wu
 */
@Slf4j
class RuleCompiler implements AutoCloseable {

    /**
     * 默认存储在锁管理器缓存中的占位键，与命名存储区分
     */
    private static final String DEFAULT_STORE_KEY = "<default>";

    private final KvStore defaultStore;
    /**
     * 默认协调存储：与 defaultStore 指向同一底层存储，仅以 KvStore 视角访问
     */
    private final KvStore defaultCoordinationStore;
    private final CasCapable defaultCas;
    private final Clock clock;
    /**
     * 锁管理器缓存：按存储名共享同一把 KvLockManager
     */
    private final ConcurrentHashMap<String, KvLockManager> lockManagers = new ConcurrentHashMap<>();
    /**
     * point → 存储名登记表，用于在规则热更新时检测并拒绝存储热切换
     */
    private final Map<String, String> activeStores = new ConcurrentHashMap<>();

    RuleCompiler(KvStore defaultStore, Clock clock) {
        // 解包装饰链直达底层存储：协调与缓存路径不经过 TieredStore / ObservedStore 等装饰层，
        // 避免本地缓存喂出陈旧的锁与会话
        this.defaultStore = unwrapStore(defaultStore);
        this.defaultCas = requireCas(this.defaultStore);
        this.defaultCoordinationStore = (KvStore) this.defaultCas;
        this.clock = clock;
    }

    /**
     * 编译一条规则：编译中途失败会回滚存储登记，保证 activeStores 与实际生效规则一致。
     */
    CompiledRule compile(String json) {
        SingleFlightRule rule = deserialize(json);
        validateRule(rule);
        if (blank(rule.getId())) {
            throw new SingleFlightConfigException("Singleflight rule id must not be empty");
        }
        return compileValidated(rule);
    }

    private SingleFlightRule deserialize(String json) {
        SingleFlightRule rule;
        try {
            rule = JsonUtil.toBean(json, SingleFlightRule.class);
        } catch (RuntimeException e) {
            throw new SingleFlightConfigException("Invalid singleflight rule json|json=" + json, e);
        }
        if (rule == null) {
            throw new SingleFlightConfigException("Singleflight rule must not be empty");
        }
        return rule;
    }

    private CompiledRule compileValidated(SingleFlightRule rule) {
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
        // 锁管理器按存储共享：同存储的多个规则复用同一续约资源，由编译器统一关闭
        KvLockManager lockManager = lockManagers.computeIfAbsent(storeKey(storeName),
                ignored -> new KvLockManager(coordinationStore, clock,
                        new KvLockManager.Config().setSpace(SingleFlightEngine.LOCK_SPACE)));
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

    private static String storeKey(String storeName) {
        return storeName.isEmpty() ? DEFAULT_STORE_KEY : storeName;
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

    /**
     * 关闭全部锁管理器并清空登记状态；可重复调用。
     */
    @Override
    public void close() {
        lockManagers.values().forEach(manager -> {
            try {
                manager.close();
            } catch (RuntimeException e) {
                log.warn("Singleflight lock manager close failed", e);
            }
        });
        lockManagers.clear();
        activeStores.clear();
    }
}
