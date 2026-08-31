package com.team4u.framework.flow.durable.engine;

import java.util.Collection;
import java.util.function.Predicate;
import com.team4u.framework.policy.core.KeyedPolicyRegistry;

/**
 * Durable 运行时父帧结果归约策略注册表。
 *
 * <p>支持注册表冻结（{@link #freeze()}）：全局注册表完成内置策略装配后冻结，
 * 冻结后的任何写操作抛出 {@link UnsupportedOperationException}，防止运行期被意外动态篡改。
 * 本地（非 global）实例不受冻结限制。</p>
 *
 * @author jay.wu
 */
public final class DurableFrameReducePolicyRegistry extends KeyedPolicyRegistry<Class<? extends DurablePlanNode>, DurableFrameReducePolicy<?>> {

    private static final DurableFrameReducePolicyRegistry GLOBAL = new DurableFrameReducePolicyRegistry();

    static {
        GLOBAL.register(new DurableFrameReducePolicies.SequenceReducePolicy());
        GLOBAL.register(new DurableFrameReducePolicies.RouteReducePolicy());
        GLOBAL.register(new DurableFrameReducePolicies.FallbackReducePolicy());
        GLOBAL.register(new DurableFrameReducePolicies.ControlReducePolicy());
        GLOBAL.freeze();
    }

    private volatile boolean frozen;

    /**
     * 获取全局共享的帧归约策略注册表（内置策略装配完毕后已冻结）。
     */
    public static DurableFrameReducePolicyRegistry global() {
        return GLOBAL;
    }

    @SuppressWarnings("unchecked")
    public DurableFrameReducePolicyRegistry() {
        super(DurableFrameReducePolicy.class);
    }

    /**
     * 冻结注册表：冻结后禁止一切写操作（不可逆）。
     */
    public void freeze() {
        this.frozen = true;
    }

    /** 是否已冻结。 */
    public boolean isFrozen() {
        return frozen;
    }

    private void rejectIfFrozen(String operation) {
        if (frozen) {
            throw new UnsupportedOperationException(
                    "DurableFrameReducePolicyRegistry is frozen; operation rejected: "
                            + operation);
        }
    }

    @Override
    public synchronized void register(DurableFrameReducePolicy<?> policy) {
        rejectIfFrozen("register");
        super.register(policy);
    }

    @Override
    public synchronized void addAll(Collection<? extends DurableFrameReducePolicy<?>> policies) {
        rejectIfFrozen("addAll");
        super.addAll(policies);
    }

    @Override
    public synchronized void addAll(com.team4u.framework.policy.api.PolicyRegistry<? extends DurableFrameReducePolicy<?>> registry) {
        rejectIfFrozen("addAll(registry)");
        super.addAll(registry);
    }

    @Override
    public synchronized void unregister(DurableFrameReducePolicy<?> policy) {
        rejectIfFrozen("unregister");
        super.unregister(policy);
    }

    @Override
    public synchronized int unregisterIf(Predicate<DurableFrameReducePolicy<?>> predicate) {
        rejectIfFrozen("unregisterIf");
        return super.unregisterIf(predicate);
    }

    @Override
    public synchronized void unregisterAll() {
        rejectIfFrozen("unregisterAll");
        super.unregisterAll();
    }
}
