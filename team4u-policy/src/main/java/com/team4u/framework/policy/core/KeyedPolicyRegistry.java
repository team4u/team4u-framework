package com.team4u.framework.policy.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.team4u.framework.policy.api.KeyedPolicy;
import com.team4u.framework.policy.api.PolicyRegistry;
import com.team4u.framework.policy.exception.PolicyException;
import lombok.Getter;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * 键值策略注册表 (查表模式)
 * <p>
 * 专用于基于 Key 的查找场景，移除不必要的上下文匹配和优先级排序逻辑。
 * 针对 getPolicies() 提供写时更新的不可变列表缓存，实现极致读取性能。
 *
 * @param <K> 路由键类型
 * @param <P> 策略类型
 */
public class KeyedPolicyRegistry<K, P extends KeyedPolicy<K>> implements PolicyRegistry<P> {

    private static final Logger log = LoggerFactory.getLogger(KeyedPolicyRegistry.class);

    /**
     * 策略类型
     */
    @Getter
    private final Class<P> policyClass;

    /**
     * 策略索引容器
     */
    private final Map<K, P> policies = new ConcurrentHashMap<>();
    /**
     * 只读策略列表缓存 (volatile 保证可见性)
     * 在每次写操作后更新，确保 getPolicies() 能够直接返回此引用。
     */
    private volatile List<P> unmodifiablePolicies = Collections.emptyList();

    @SuppressWarnings("unchecked")
    public KeyedPolicyRegistry(Class<?> policyClass) {
        this.policyClass = (Class<P>) policyClass;
    }

    @Override
    public synchronized void register(P policy) {
        if (!isValidPolicy(policy)) {
            return;
        }
        policies.put(policy.key(), policy);
        updateCache();
        log.info("KeyedPolicyRegistry|register|success|policyClass={}|key={}|count={}",
                policyClass.getSimpleName(), policy.key(), policies.size());
    }

    @Override
    public synchronized void addAll(Collection<? extends P> policies) {
        if (policies == null || policies.isEmpty()) {
            return;
        }
        int addedCount = 0;
        for (P policy : policies) {
            if (isValidPolicy(policy)) {
                this.policies.put(policy.key(), policy);
                addedCount++;
            }
        }
        if (addedCount > 0) {
            updateCache();
            log.info("KeyedPolicyRegistry|addAll|success|policyClass={}|addedCount={}|totalCount={}",
                    policyClass.getSimpleName(), addedCount, this.policies.size());
        }
    }

    /**
     * 批量注册另一个注册表的所有策略
     * <p>
     * 仅支持同类型的 KeyedPolicyRegistry，直接使用 putAll 合并
     *
     * @param registry 另一个策略注册表
     * @throws IllegalArgumentException 如果 registry 不是 KeyedPolicyRegistry 类型
     */
    @Override
    @SuppressWarnings("unchecked")
    public synchronized void addAll(PolicyRegistry<? extends P> registry) {
        if (!(registry instanceof KeyedPolicyRegistry)) {
            throw PolicyException.unsupportedRegistry(KeyedPolicyRegistry.class, registry.getClass());
        }
        KeyedPolicyRegistry<K, ? extends P> other = (KeyedPolicyRegistry<K, ? extends P>) registry;
        if (other.policies.isEmpty()) {
            return;
        }
        int addedCount = other.policies.size();
        policies.putAll(other.policies);
        updateCache();
        log.info("KeyedPolicyRegistry|addAll|success|policyClass={}|addedCount={}|totalCount={}",
                policyClass.getSimpleName(), addedCount, policies.size());
    }

    /**
     * 验证策略是否有效
     *
     * @param policy 待验证的策略
     * @return true 如果策略有效，false 如果为 null
     * @throws PolicyException 如果策略类型不匹配
     */
    private boolean isValidPolicy(P policy) {
        if (policy == null) {
            return false;
        }
        if (!policyClass.isInstance(policy)) {
            throw PolicyException.typeMismatch(policyClass, policy.getClass());
        }
        return true;
    }

    private void updateCache() {
        this.unmodifiablePolicies = Collections.unmodifiableList(new ArrayList<>(policies.values()));
    }

    @Override
    public synchronized void unregister(P policy) {
        if (policy == null) {
            return;
        }
        if (policies.remove(policy.key(), policy)) {
            updateCache();
        }
    }

    @Override
    public synchronized int unregisterIf(Predicate<P> predicate) {
        int originalSize = policies.size();
        policies.values().removeIf(predicate);
        int removedCount = originalSize - policies.size();

        if (removedCount > 0) {
            updateCache();
        }

        return removedCount;
    }

    @Override
    public synchronized void unregisterAll() {
        policies.clear();
        unmodifiablePolicies = Collections.emptyList();
    }

    /**
     * 根据标识键进行精准路由匹配
     *
     * @param key 路由键
     * @return 匹配的策略实例，若不存在则返回 empty
     */
    public Optional<P> get(K key) {
        if (key == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(policies.get(key));
    }

    /**
     * 获取所有已注册的策略列表
     * <p>
     * 使用缓存的不可变列表，彻底消除读取时的 ArrayList 创建。
     *
     * @return 策略列表
     */
    @Override
    public List<P> getPolicies() {
        return unmodifiablePolicies;
    }
}
