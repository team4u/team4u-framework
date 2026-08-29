package com.team4u.framework.kv;

import com.team4u.framework.policy.api.PolicyRegistry;
import com.team4u.framework.policy.core.KeyedPolicyRegistry;
import com.team4u.framework.policy.spring.PolicyAutoRegister;
import lombok.Getter;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * 命名 KV 存储注册表：按名字注册/查找 {@link KvStore} 的公共注册中心
 * <p>
 * 限流（RateLimitStores）、回源合并（SingleFlightStores）、序号（SeqStores）
 * 等场景均以 {@code store} 字段按名引用存储，实现「一套规则、多存储分工」
 * （如默认走内存、热点检查点走 Redis）。本注册表把该模式收拢为 kv-core 的
 * 公共基建，同名重新注册即热更新（后注册者覆盖先注册者）。
 * </p>
 * <h3>Spring 装配</h3>
 * <p>
 * 本类实现 {@link PolicyRegistry} 并标注 {@link PolicyAutoRegister}——声明为
 * Spring Bean 后，{@link com.team4u.framework.policy.spring.SpringPolicyAutoRegistrar}
 * 会在容器初始化完成时把容器内所有 {@link NamedKvStore} 类型的 Bean 批量注入：
 * </p>
 * <pre>{@code
 * @Bean
 * public NamedKvStoreRegistry kvStores() {
 *     return new NamedKvStoreRegistry();
 * }
 *
 * @Bean
 * public NamedKvStore redisStore(StringRedisTemplate redis) {
 *     return new NamedKvStore("redis", new RedisKvStore(redis));
 * }
 * }</pre>
 * <p>
 * 无 Spring 环境直接使用 {@link #global()} 单例或独立实例。
 * </p>
 *
 * @author jay.wu
 */
@PolicyAutoRegister
public class NamedKvStoreRegistry implements PolicyRegistry<NamedKvStore> {

    private static final NamedKvStoreRegistry GLOBAL = new NamedKvStoreRegistry();

    @Getter
    private final Class<NamedKvStore> policyClass = NamedKvStore.class;

    private final KeyedPolicyRegistry<String, NamedKvStore> registry =
            new KeyedPolicyRegistry<>(NamedKvStore.class);

    /**
     * 全局单例：与框架其他全局注册表（Spaces.global() 等）使用习惯一致
     */
    public static NamedKvStoreRegistry global() {
        return GLOBAL;
    }

    /**
     * 注册命名存储（同名后注册者覆盖先注册者，即热更新）
     *
     * @return this，支持链式调用
     * @throws IllegalArgumentException 名字为空或存储为 {@code null}
     */
    public NamedKvStoreRegistry register(String name, KvStore store) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Store name must not be blank");
        }
        Objects.requireNonNull(store, "store");
        registry.register(new NamedKvStore(name, store));
        return this;
    }

    /**
     * 按名解析存储
     *
     * @return 绑定的存储实例
     * @throws IllegalArgumentException 名字未注册（异常消息含存储名）
     */
    public KvStore get(String name) {
        return registry.get(name)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Kv store not registered: " + name))
                .getStore();
    }

    /**
     * 按名解析存储，未注册返回默认存储而非抛异常
     *
     * @param defaultStore 名字未注册时返回的存储
     * @return 绑定的存储实例；未注册时为 {@code defaultStore}
     */
    public KvStore getOrDefault(String name, KvStore defaultStore) {
        return registry.get(name).map(NamedKvStore::getStore).orElse(defaultStore);
    }

    /**
     * 查询名字是否已注册
     */
    public boolean contains(String name) {
        return registry.get(name).isPresent();
    }

    /**
     * 已注册的全部存储名（只读、字典序；返回快照，后续变更不影响已返回集合）
     */
    public Set<String> names() {
        Set<String> names = new TreeSet<>();
        for (NamedKvStore named : registry.getPolicies()) {
            names.add(named.getName());
        }
        return Collections.unmodifiableSet(names);
    }

    /**
     * 注销指定名字的存储
     *
     * @return {@code true} 注销成功；名字未注册返回 {@code false}
     */
    public boolean unregister(String name) {
        java.util.Optional<NamedKvStore> named = registry.get(name);
        if (!named.isPresent()) {
            return false;
        }
        registry.unregister(named.get());
        return true;
    }

    /**
     * 清空全部已注册存储
     */
    public void clear() {
        registry.unregisterAll();
    }

    // ------------------------------------------------- PolicyRegistry 装配面
    // Spring 装配（SpringPolicyAutoRegistrar 的 addAll 注入）与直接策略注册走这一面；
    // 业务方按名字使用时走上面的 register(name, store)/get(name) 简化面

    @Override
    public void register(NamedKvStore policy) {
        registry.register(policy);
    }

    @Override
    public void addAll(Collection<? extends NamedKvStore> policies) {
        registry.addAll(policies);
    }

    @Override
    public void addAll(PolicyRegistry<? extends NamedKvStore> other) {
        registry.addAll(other);
    }

    @Override
    public void unregister(NamedKvStore policy) {
        registry.unregister(policy);
    }

    @Override
    public int unregisterIf(Predicate<NamedKvStore> predicate) {
        return registry.unregisterIf(predicate);
    }

    @Override
    public void unregisterAll() {
        clear();
    }

    @Override
    public List<NamedKvStore> getPolicies() {
        return registry.getPolicies();
    }
}
