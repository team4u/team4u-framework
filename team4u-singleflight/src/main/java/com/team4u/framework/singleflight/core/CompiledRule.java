package com.team4u.framework.singleflight.core;

import com.team4u.framework.kv.CasCapable;
import com.team4u.framework.kv.KvStore;
import com.team4u.framework.kv.lock.KvLockManager;
import com.team4u.framework.singleflight.config.SingleFlightRule;
import com.team4u.framework.singleflight.policy.KeyResolver;
import com.team4u.framework.singleflight.policy.SingleFlightCondition;

/**
 * 编译后的规则及其运行期资源（预解析的条件、键模板、存储视图与锁管理器）。
 * <p>
 * 由 {@link RuleCompiler} 在规则加载期产出，{@link SessionCoordinator} 在执行期消费——
 * 两者只经由本类型交互，编译关注「配置如何变为可执行形态」，协调关注「执行窗口如何流转」。
 * </p>
 * <p>
 * {@link com.team4u.framework.config.core.support.ConfigDrivenRegistry} 保证：仅在新实例
 * 构建成功后才关闭被替换的旧实例，规则热更新期间旧资源始终可用。
 * {@code KvLockManager} 按命名存储共享，不随规则换代关闭，仅由引擎统一释放。
 * </p>
 *
 * @author jay.wu
 */
final class CompiledRule {

    private final SingleFlightRule rule;
    private final SingleFlightCondition skipWhen;
    private final SingleFlightCondition cacheWhen;
    private final KeyResolver keyResolver;
    private final KvStore resultStore;
    private final KvStore coordinationStore;
    private final CasCapable cas;
    private final KvLockManager lockManager;

    CompiledRule(SingleFlightRule rule, SingleFlightCondition skipWhen,
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

    SingleFlightRule rule() {
        return rule;
    }

    SingleFlightCondition skipWhen() {
        return skipWhen;
    }

    SingleFlightCondition cacheWhen() {
        return cacheWhen;
    }

    KeyResolver keyResolver() {
        return keyResolver;
    }

    KvStore resultStore() {
        return resultStore;
    }

    KvStore coordinationStore() {
        return coordinationStore;
    }

    CasCapable cas() {
        return cas;
    }

    KvLockManager lockManager() {
        return lockManager;
    }
}
