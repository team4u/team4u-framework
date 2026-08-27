package com.team4u.framework.id.group;

import com.team4u.framework.policy.core.KeyedPolicyRegistry;

import java.util.Optional;

/**
 * 分组策略注册表与全局门面
 * <p>
 * 基于 {@link KeyedPolicyRegistry} 管理 {@link GroupKeyPolicy}，默认注册内置的
 * {@link DateGroupKeyPolicy}（{@code DATE}）与 {@link ExtGroupKeyPolicy}（{@code EXT}）。
 * 自定义策略注册到 {@link #global()} 即对全部序号服务实例生效，同名重新注册即热更新
 * ——与 {@code Spaces.global()} 使用习惯一致。
 * </p>
 * Spring 环境可将全局注册表声明为 Bean 并配合策略自动发现：
 * <pre>{@code
 * @Bean
 * @PolicyAutoRegister
 * public PolicyRegistry<GroupKeyPolicy> groupKeyPolicies() {
 *     return GroupKeyPolicies.global().registry();
 * }
 * }</pre>
 *
 * @author jay.wu
 */
public class GroupKeyPolicies {

    private static final GroupKeyPolicies GLOBAL = new GroupKeyPolicies();

    private final KeyedPolicyRegistry<String, GroupKeyPolicy> registry =
            new KeyedPolicyRegistry<>(GroupKeyPolicy.class);

    public GroupKeyPolicies() {
        registry.register(DateGroupKeyPolicy.INSTANCE);
        registry.register(ExtGroupKeyPolicy.INSTANCE);
    }

    /**
     * 全局单例
     */
    public static GroupKeyPolicies global() {
        return GLOBAL;
    }

    /**
     * 底层注册表（Spring 策略自动发现等场景使用）
     */
    public KeyedPolicyRegistry<String, GroupKeyPolicy> registry() {
        return registry;
    }

    /**
     * 注册分组策略（同名校略覆盖）
     */
    public GroupKeyPolicies register(GroupKeyPolicy policy) {
        registry.register(policy);
        return this;
    }

    /**
     * 查询分组策略
     */
    public Optional<GroupKeyPolicy> get(String type) {
        return registry.get(type);
    }
}
