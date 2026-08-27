package com.team4u.framework.kv.space;

import com.team4u.framework.kv.KvStore;
import com.team4u.framework.policy.core.KeyedPolicyRegistry;

import java.time.Clock;
import java.util.Optional;

/**
 * 键空间注册表与门面工厂
 * <p>
 * 基于 {@link KeyedPolicyRegistry}（Copy-On-Write，读路径无锁、零 GC）管理
 * {@link SpacePolicy}。策略重新注册即热更新，已构建的 {@link Space} 不受影响，
 * 新构建的 {@link Space} 使用新策略——与配置中心的快照热更语义一致。
 * </p>
 * 使用方式：
 * <pre>{@code
 * Spaces.global().register(new SpacePolicy()
 *         .setName("user.session")
 *         .setValueType(Session.class)
 *         .setDefaultTtlMillis(3600_000));
 *
 * Space<Session> sessions = Spaces.global().use("user.session", kvStore);
 * }</pre>
 *
 * @author jay.wu
 */
public class Spaces {

    private static final Spaces GLOBAL = new Spaces();

    private final KeyedPolicyRegistry<String, SpacePolicy> registry =
            new KeyedPolicyRegistry<>(SpacePolicy.class);
    private final Clock clock;

    public Spaces() {
        this(Clock.systemUTC());
    }

    /**
     * @param clock 写入默认 TTL 时使用的时钟，测试可注入虚拟时钟
     */
    public Spaces(Clock clock) {
        this.clock = clock;
    }

    /**
     * 全局单例：与框架其他全局注册表（如 ConfigManager.global()）使用习惯一致
     */
    public static Spaces global() {
        return GLOBAL;
    }

    /**
     * 注册（同名校略策略覆盖，实现热更新）
     */
    public synchronized Spaces register(SpacePolicy policy) {
        registry.register(policy);
        return this;
    }

    /**
     * 注销指定键空间策略
     */
    public synchronized Spaces unregister(String name) {
        registry.get(name).ifPresent(registry::unregister);
        return this;
    }

    /**
     * 查询键空间策略
     */
    public Optional<SpacePolicy> policy(String name) {
        return registry.get(name);
    }

    /**
     * 按已注册的策略构建类型化门面
     *
     * @throws IllegalArgumentException 键空间未注册
     */
    public <V> Space<V> use(String name, KvStore store) {
        SpacePolicy policy = registry.get(name)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Space not registered: " + name + ", register SpacePolicy first"));
        return new Space<>(store, policy, clock);
    }
}
