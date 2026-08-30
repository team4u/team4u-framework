package com.team4u.framework.config.core.convert;

import com.team4u.framework.policy.core.KeyedPolicyRegistry;

/**
 * 属性转换器注册表
 * <p>
 * 用于统一管理和检索已注册的 {@link PropertyConverter} 实例。
 * 继承自 {@link KeyedPolicyRegistry}，通过转换器的类名进行唯一标识。
 * </p>
 */
public class PropertyConverterRegistry
        extends KeyedPolicyRegistry<Class<? extends PropertyConverter<?>>, PropertyConverter<?>> {

    private static final PropertyConverterRegistry GLOBAL = new PropertyConverterRegistry();

    /**
     * 获取全局共享的属性转换器注册表实例
     *
     * @return 全局注册表实例
     */
    public static PropertyConverterRegistry global() {
        return GLOBAL;
    }

    /**
     * 创建一个新的属性转换器注册表
     * <p>
     * 推荐在需要完全隔离的测试环境或多配置中心并存的场景下手动创建。
     * 一般建议使用 {@link #global()} 获取全局共享实例。
     * </p>
     */
    public PropertyConverterRegistry() {
        super(PropertyConverter.class);
    }
}
