package com.team4u.framework.policy;

import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;

/**
 * 策略注册表顶层接口
 *
 * @param <P> 策略类型
 */
public interface PolicyRegistry<P> {

    /**
     * 注册策略
     *
     * @param policy 待注册的具体策略实现类对象
     */
    void register(P policy);

    /**
     * 批量注册策略
     *
     * @param policies 待注册的策略集合
     */
    void addAll(Collection<? extends P> policies);

    /**
     * 批量注册另一个注册表的所有策略
     *
     * @param registry 另一个策略注册表
     */
    void addAll(PolicyRegistry<? extends P> registry);

    /**
     * 注销指定策略实例
     *
     * @param policy 要注销的策略对象
     */
    void unregister(P policy);

    /**
     * 函数式动态注销
     *
     * @param predicate 匹配条件
     * @return 成功移出的策略数量
     */
    int unregisterIf(Predicate<P> predicate);

    /**
     * 按策略类型注销
     *
     * @param policyClass 策略类
     * @return 移出数量
     */
    default int unregisterByType(Class<? extends P> policyClass) {
        return unregisterIf(p -> p.getClass().equals(policyClass));
    }

    /**
     * 清空所有策略
     */
    void unregisterAll();

    /**
     * 获取所有已注册的策略列表
     *
     * @return 策略列表
     */
    List<P> getPolicies();

    /**
     * 获取策略类型
     *
     * @return 策略类型的 Class 对象
     */
    Class<P> getPolicyClass();
}
