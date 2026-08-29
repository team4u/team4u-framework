package com.team4u.framework.singleflight.store;

import com.team4u.framework.kv.KvStore;
import com.team4u.framework.policy.core.KeyedPolicyRegistry;

/**
 * 回源合并规则的命名 KvStore 注册表：规则以 {@code store} 字段按名引用存储，
 * 引擎在规则加载期解析并校验 CAS 能力。
 * <p>
 * 全局单例（{@link #global()}）；未注册的名字在规则加载期即失败。
 * </p>
 *
 * @author jay.wu
 */
public class SingleFlightStores {

    private static final SingleFlightStores GLOBAL = new SingleFlightStores();

    private final KeyedPolicyRegistry<String, NamedStore> registry =
            new KeyedPolicyRegistry<>(NamedStore.class);

    /**
     * 全局注册表实例。
     */
    public static SingleFlightStores global() {
        return GLOBAL;
    }

    /**
     * 注册命名存储（同名后注册者覆盖先注册者）。
     */
    public SingleFlightStores register(String name, KvStore store) {
        registry.register(new NamedStore(name, store));
        return this;
    }

    /**
     * 按名解析存储；未注册抛 {@link IllegalArgumentException}，由引擎转配置异常。
     */
    public KvStore resolve(String name) {
        return registry.get(name)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Singleflight store not registered: " + name))
                .getStore();
    }
}
