package com.team4u.framework.flow.durable.engine;

import java.util.Collection;
import java.util.function.Predicate;
import com.team4u.framework.flow.spi.ControlKind;
import com.team4u.framework.policy.core.KeyedPolicyRegistry;

/**
 * Durable 治理控制类型策略注册表。
 *
 * <p>支持注册表冻结（{@link #freeze()}）：全局注册表完成内置策略装配后冻结，
 * 冻结后的任何写操作（register/unregister 等）抛出 {@link UnsupportedOperationException}，
 * 防止运行期被意外动态篡改导致集群行为漂移。本地（非 global）实例不受冻结限制。</p>
 *
 * @author jay.wu
 */
public final class DurableControlKindRegistry extends KeyedPolicyRegistry<ControlKind, DurableControlKindHandler> {

    private static final DurableControlKindRegistry GLOBAL = new DurableControlKindRegistry();

    static {
        GLOBAL.register(new DurableControlKindHandlers.TimeoutHandler());
        GLOBAL.register(new DurableControlKindHandlers.PolicyHandler());
        GLOBAL.register(new DurableControlKindHandlers.PersistentPolicyHandler());
        GLOBAL.freeze();
    }

    private volatile boolean frozen;

    /**
     * 获取全局共享的 Durable 控制类型策略注册表（内置策略装配完毕后已冻结）。
     */
    public static DurableControlKindRegistry global() {
        return GLOBAL;
    }

    public DurableControlKindRegistry() {
        super(DurableControlKindHandler.class);
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
                    "DurableControlKindRegistry is frozen; operation rejected: " + operation);
        }
    }

    @Override
    public synchronized void register(DurableControlKindHandler policy) {
        rejectIfFrozen("register");
        super.register(policy);
    }

    @Override
    public synchronized void addAll(Collection<? extends DurableControlKindHandler> policies) {
        rejectIfFrozen("addAll");
        super.addAll(policies);
    }

    @Override
    public synchronized void addAll(com.team4u.framework.policy.api.PolicyRegistry<? extends DurableControlKindHandler> registry) {
        rejectIfFrozen("addAll(registry)");
        super.addAll(registry);
    }

    @Override
    public synchronized void unregister(DurableControlKindHandler policy) {
        rejectIfFrozen("unregister");
        super.unregister(policy);
    }

    @Override
    public synchronized int unregisterIf(Predicate<DurableControlKindHandler> predicate) {
        rejectIfFrozen("unregisterIf");
        return super.unregisterIf(predicate);
    }

    @Override
    public synchronized void unregisterAll() {
        rejectIfFrozen("unregisterAll");
        super.unregisterAll();
    }
}
