package com.team4u.framework.ratelimiter.store;

import com.team4u.framework.kv.KvStore;
import com.team4u.framework.policy.core.KeyedPolicyRegistry;

/**
 * 限流存储注册表与全局门面
 * <p>
 * 限流规则通过 {@code store} 字段按名引用存储，实现「一套规则、多存储分工」
 * （如默认走内存、热点检查点走 Redis）。同名重新注册即热更新。
 * </p>
 *
 * @author jay.wu
 */
public class RateLimitStores {

    private static final RateLimitStores GLOBAL = new RateLimitStores();

    private final KeyedPolicyRegistry<String, NamedStore> registry =
            new KeyedPolicyRegistry<>(NamedStore.class);

    /**
     * 全局单例
     */
    public static RateLimitStores global() {
        return GLOBAL;
    }

    /**
     * 注册命名存储
     */
    public RateLimitStores register(String name, KvStore store) {
        registry.register(new NamedStore(name, store));
        return this;
    }

    /**
     * 按名解析存储
     *
     * @throws IllegalArgumentException 存储名未注册
     */
    public KvStore resolve(String name) {
        NamedStore named = registry.get(name)
                .orElseThrow(() -> new IllegalArgumentException("Rate limit store not registered: " + name));
        return named.getStore();
    }
}
