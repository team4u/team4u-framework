package com.team4u.framework.id.store;

import com.team4u.framework.kv.KvStore;
import com.team4u.framework.policy.core.KeyedPolicyRegistry;

/**
 * 序号存储注册表与全局门面
 * <p>
 * 序号规则通过 {@code store} 字段按名引用存储，实现「一套规则、多存储分工」
 * （如默认走数据库、高频序号走 Redis）。本注册表与 {@code Spaces.global()}、
 * {@code GroupKeyPolicies.global()} 使用习惯一致，同名重新注册即热更新。
 * </p>
 *
 * @author jay.wu
 */
public class SeqStores {

    private static final SeqStores GLOBAL = new SeqStores();

    private final KeyedPolicyRegistry<String, NamedStore> registry =
            new KeyedPolicyRegistry<>(NamedStore.class);

    /**
     * 全局单例
     */
    public static SeqStores global() {
        return GLOBAL;
    }

    /**
     * 注册命名存储
     */
    public SeqStores register(String name, KvStore store) {
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
                .orElseThrow(() -> new IllegalArgumentException("Seq store not registered: " + name));
        return named.getStore();
    }
}
