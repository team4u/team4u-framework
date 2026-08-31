package com.team4u.framework.flow.compiler;

import com.team4u.framework.policy.api.KeyedPolicy;
import com.team4u.framework.policy.core.KeyedPolicyRegistry;

import java.util.Collection;
import java.util.function.Predicate;

/**
 * 流程核心注册表冻结基类（Frozen Keyed Policy Registry）。
 *
 * <p>流程内核的策略注册表（节点执行、帧归约、控制类型、降级编译、计划投影、结构描述）
 * 均在类静态初始化阶段完成注册，之后进入只读状态。本基类在 {@link KeyedPolicyRegistry}
 * 之上提供冻结机制：</p>
 * <ul>
 *   <li>{@link #freeze()} 之后，任何 register / unregister / unregisterIf / unregisterAll
 *   写入操作都会抛出 {@link UnsupportedOperationException}，防止运行期被意外篡改全局内核行为；</li>
 *   <li>读取操作（get / getPolicies / 扩展点查找）不受冻结影响；</li>
 *   <li>基座模块 team4u-policy 的 KeyedPolicyRegistry 面向大量可动态变更的业务注册场景，
 *   不宜全局加入冻结语义，因此冻结能力由 flow core 各注册表子类覆写写入方法实现。</li>
 * </ul>
 *
 * @param <K> 路由键类型
 * @param <P> 策略类型
 * @author jay.wu
 */
public abstract class FrozenKeyedPolicyRegistry<K, P extends KeyedPolicy<K>>
        extends KeyedPolicyRegistry<K, P> {

    private volatile boolean frozen;

    /**
     * 构造冻结注册表基类。
     *
     * @param policyClass 策略类型
     */
    protected FrozenKeyedPolicyRegistry(Class<?> policyClass) {
        super(policyClass);
    }

    /**
     * 冻结注册表：此后所有写入操作抛出 {@link UnsupportedOperationException}。
     *
     * <p>幂等：重复调用安全。</p>
     */
    public void freeze() {
        frozen = true;
    }

    /**
     * 检查注册表是否已冻结。
     *
     * @return 若已冻结返回 true，否则返回 false
     */
    public boolean isFrozen() {
        return frozen;
    }

    private void refuseIfFrozen(String operation) {
        if (frozen) {
            throw new UnsupportedOperationException(
                    getPolicyClass().getSimpleName() + " registry is frozen; "
                            + operation + " is not allowed after static registration");
        }
    }

    @Override
    public synchronized void register(P policy) {
        refuseIfFrozen("register");
        super.register(policy);
    }

    @Override
    public synchronized void addAll(Collection<? extends P> policies) {
        refuseIfFrozen("addAll");
        super.addAll(policies);
    }

    @Override
    public synchronized void unregister(P policy) {
        refuseIfFrozen("unregister");
        super.unregister(policy);
    }

    @Override
    public synchronized int unregisterIf(Predicate<P> predicate) {
        refuseIfFrozen("unregisterIf");
        return super.unregisterIf(predicate);
    }

    @Override
    public synchronized void unregisterAll() {
        refuseIfFrozen("unregisterAll");
        super.unregisterAll();
    }
}
