package com.team4u.framework.singleflight.policy;

import com.team4u.framework.policy.core.KeyedPolicyRegistry;

/**
 * 业务 key 摘要策略注册表：规则以 {@code keyDigest} 字段按名引用摘要算法，
 * 引擎在规则加载期解析并固定实例，运行期零查找开销。
 * <p>
 * 全局单例（{@link #global()}），内置 {@code sha256}；自定义算法（如 HMAC）
 * 在应用启动时注册，同名后注册者覆盖先注册者（与 {@code SingleFlightStores} 行为一致）。
 * 未注册的名字在规则加载期即失败。
 * </p>
 *
 * @author jay.wu
 */
public final class SingleFlightKeyDigests {

    private static final SingleFlightKeyDigests GLOBAL = new SingleFlightKeyDigests();

    static {
        GLOBAL.register(new Sha256KeyDigest());
    }

    private final KeyedPolicyRegistry<String, SingleFlightKeyDigest> registry =
            new KeyedPolicyRegistry<>(SingleFlightKeyDigest.class);

    private SingleFlightKeyDigests() {
    }

    /**
     * 全局注册表实例。
     */
    public static SingleFlightKeyDigests global() {
        return GLOBAL;
    }

    /**
     * 注册摘要策略（同名后注册者覆盖先注册者），返回 this 支持链式注册。
     */
    public SingleFlightKeyDigests register(SingleFlightKeyDigest digest) {
        registry.register(digest);
        return this;
    }

    /**
     * 按名解析摘要策略；未注册抛 {@link IllegalArgumentException}，由规则编译器转配置异常。
     */
    public SingleFlightKeyDigest resolve(String name) {
        return registry.get(name)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Singleflight key digest not registered: " + name));
    }
}
