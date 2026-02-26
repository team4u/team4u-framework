package com.team4u.framework.config.core.proxy;

import cn.hutool.core.util.ReflectUtil;
import com.team4u.framework.config.core.ConfigManager;
import com.team4u.framework.config.core.convert.PropertyConverterRegistry;
import com.team4u.framework.config.core.domain.ConfigSnapshot;
import com.team4u.framework.proxy.ProxyBuilder;

import java.util.function.Supplier;

/**
 * 动态代理工厂
 * <p>
 * 支持 Java Bean 模式，代理对象能够自动感知底层的配置快照变化，并执行相应的类型转换。
 * Bean 字段的初始值作为配置缺失时的兜底默认值。
 * </p>
 */
public class ConfigProxyFactory {

    /**
     * 属性转换器注册表，用于在创建代理时传递给拦截器
     */
    private final PropertyConverterRegistry converterRegistry;

    public ConfigProxyFactory(PropertyConverterRegistry converterRegistry) {
        this.converterRegistry = converterRegistry;
    }

    /**
     * 创建支持实时热重载的动态代理实例
     *
     * @param manager 全局配置管理门面
     * @param prefix  配置键前缀
     * @param type    目标 Bean 类型
     * @param <T>     强类型
     * @return 代理对象实例
     */
    public <T> T createLiveProxy(ConfigManager manager, String prefix, Class<T> type) {
        // 使用 SnapshotManager 的实时快照提供者
        return createProxy(manager::currentSnapshot, prefix, type, false);
    }

    /**
     * 创建“快照锚定”模式的代理实例
     *
     * @param fixedSnapshot 被绑定的固定快照实例
     * @param prefix        配置键前缀
     * @param type          目标 Bean 类型
     * @param <T>           强类型
     * @return 代理对象实例
     */
    public <T> T createPinnedProxy(ConfigSnapshot fixedSnapshot, String prefix, Class<T> type) {
        // 使用固定的快照提供者，配置值不再随源变化而更新
        return createProxy(() -> fixedSnapshot, prefix, type, true);
    }

    /**
     * 统一代理实例构建逻辑
     *
     * @param snapshotProvider 快照提供者（支持动态或固定）
     * @param prefix           配置键前缀
     * @param type             目标 Bean 类型
     * @param isPinned         是否为锚定模式
     * @param <T>              强类型
     * @return 代理对象实例
     */
    public <T> T createProxy(Supplier<ConfigSnapshot> snapshotProvider,
                             String prefix, Class<T> type,
                             boolean isPinned) {
        // 构建方法拦截器，处理配置解析核心逻辑
        ConfigMethodInterceptor interceptor = new ConfigMethodInterceptor(
                type,
                prefix,
                snapshotProvider,
                isPinned,
                this,
                converterRegistry);

        // 实例化真实 Bean 对象作为委托对象，确保能够保留字段的初始值作为配置缺失时的默认值
        T delegate;
        try {
            delegate = ReflectUtil.newInstance(type);
        } catch (Exception e) {
            throw new IllegalArgumentException("实例化配置 Bean 失败，请确保类型 [" + type.getName() + "] 包含无参构造函数", e);
        }

        // 使用 ProxyBuilder 构建代理实例，注入拦截器和委托对象
        return ProxyBuilder.forClass(type)
                .withInterfaces(SnapshotAware.class)
                .addInterceptor(interceptor)
                .withDelegate(delegate)
                .build();
    }
}
