package com.team4u.framework.policy.core;

import cn.hutool.log.Log;
import com.team4u.framework.policy.api.ContextPolicy;
import com.team4u.framework.policy.api.PolicyRegistry;
import com.team4u.framework.policy.exception.PolicyException;
import lombok.Getter;

import java.util.*;
import java.util.function.Predicate;

/**
 * 有序策略链 (责任链模式)
 * <p>
 * 专用于基于上下文匹配和优先级排序的场景。
 * 采用手动写时复制 (Manual Copy-On-Write) 机制，确保读取操作 getPolicies() 达到极致性能 (零对象创建)。
 *
 * @param <C> 上下文类型
 * @param <P> 策略类型
 */
@Getter
public class OrderedPolicyChain<C, P extends ContextPolicy<C>> implements PolicyRegistry<P> {

    private static final Log log = Log.get();

    /**
     * 策略类型
     */
    private final Class<P> policyClass;

    /**
     * 策略列表缓存 (volatile 保证可见性，存储不可变列表)
     */
    private volatile List<P> policies = Collections.emptyList();

    public OrderedPolicyChain(Class<P> policyClass) {
        this.policyClass = policyClass;
    }

    @Override
    public synchronized void register(P policy) {
        if (!isValidPolicy(policy)) {
            return;
        }

        List<P> newPolicies = new ArrayList<>(policies);
        newPolicies.removeIf(p -> p.getClass().equals(policy.getClass()));
        newPolicies.add(policy);
        updatePolicies(newPolicies);

        log.info("OrderedPolicyChain|register|success|policyClass={}|policy={}|count={}",
                policyClass.getSimpleName(), policy.getClass().getSimpleName(), policies.size());
    }

    @Override
    public synchronized void addAll(Collection<? extends P> policies) {
        if (policies == null || policies.isEmpty()) {
            return;
        }

        List<P> newPolicies = new ArrayList<>(this.policies);
        int addedCount = 0;
        for (P policy : policies) {
            if (!isValidPolicy(policy)) {
                continue;
            }
            newPolicies.removeIf(p -> p.getClass().equals(policy.getClass()));
            newPolicies.add(policy);
            addedCount++;
        }

        if (addedCount > 0) {
            updatePolicies(newPolicies);
            log.info("OrderedPolicyChain|addAll|success|policyClass={}|addedCount={}|totalCount={}",
                    policyClass.getSimpleName(), addedCount, this.policies.size());
        }
    }

    /**
     * 批量注册另一个注册表的所有策略
     * <p>
     * 仅支持同类型的 OrderedPolicyChain
     *
     * @param registry 另一个策略注册表
     * @throws IllegalArgumentException 如果 registry 不是 OrderedPolicyChain 类型
     */
    @Override
    public synchronized void addAll(PolicyRegistry<? extends P> registry) {
        if (!(registry instanceof OrderedPolicyChain)) {
            throw PolicyException.unsupportedRegistry(OrderedPolicyChain.class, registry.getClass());
        }
        addAll(registry.getPolicies());
    }

    /**
     * 验证策略是否有效
     *
     * @param policy 待验证的策略
     * @return true 如果策略有效，false 如果为 null
     * @throws PolicyException 如果策略类型不匹配
     */
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private boolean isValidPolicy(P policy) {
        if (policy == null) {
            return false;
        }
        if (!policyClass.isInstance(policy)) {
            throw PolicyException.typeMismatch(policyClass, policy.getClass());
        }
        return true;
    }

    /**
     * 更新策略列表并排序
     *
     * @param newPolicies 新的策略列表
     */
    private void updatePolicies(List<P> newPolicies) {
        Collections.sort(newPolicies);
        this.policies = Collections.unmodifiableList(newPolicies);
    }

    @Override
    public synchronized void unregister(P policy) {
        if (policy == null) {
            return;
        }

        List<P> newPolicies = new ArrayList<>(policies);
        if (newPolicies.remove(policy)) {
            this.policies = Collections.unmodifiableList(newPolicies);
        }
    }

    @Override
    public synchronized int unregisterIf(Predicate<P> predicate) {
        List<P> newPolicies = new ArrayList<>(policies);
        if (newPolicies.removeIf(predicate)) {
            int removedCount = policies.size() - newPolicies.size();
            this.policies = Collections.unmodifiableList(newPolicies);
            return removedCount;
        }
        return 0;
    }

    @Override
    public synchronized void unregisterAll() {
        this.policies = Collections.emptyList();
    }

    /**
     * 获取首个匹配的策略
     *
     * @param context 业务上下文
     * @return 匹配的策略实例，若无匹配项则返回 empty
     */
    public Optional<P> firstMatch(C context) {
        // 直接读取 volatile 引用，无需加锁
        for (P policy : policies) {
            if (policy.supports(context)) {
                return Optional.of(policy);
            }
        }
        return Optional.empty();
    }

    /**
     * 获取所有匹配的策略
     *
     * @param context 业务上下文
     * @return 匹配的策略实例列表
     */
    public List<P> allMatches(C context) {
        List<P> result = new ArrayList<>();
        for (P policy : policies) {
            if (policy.supports(context)) {
                result.add(policy);
            }
        }
        return result;
    }
}
