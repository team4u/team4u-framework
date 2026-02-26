package com.team4u.framework.config.core.proxy;

import com.team4u.framework.config.core.ConfigManager;
import com.team4u.framework.config.core.convert.PropertyConverterRegistry;
import com.team4u.framework.config.core.domain.ConfigSnapshot;
import com.team4u.framework.proxy.ProxyBuilder;

import java.util.function.Supplier;

/**
 * 动态代理工厂
 * <p>
 * 基于 team4u-proxy 统一构建配置代理实例。
 * 代理对象能够自动感知底层的配置快照变化，并执行相应的类型转换。
 * </p>
 */
public class ConfigProxyFactory {

    private final PropertyConverterRegistry converterRegistry;

    public ConfigProxyFactory(PropertyConverterRegistry converterRegistry) {
        this.converterRegistry = converterRegistry;
    }

    /**
     * 创建支持实时热重载的动态代理实例
     *
     * @param manager 全局配置管理门面
     * @param prefix  配置键前缀
     * @param type    目标类型（接口或类）
     * @param <T>     强类型
     * @return 代理对象实例
     */
    public <T> T createLiveProxy(ConfigManager manager, String prefix, Class<T> type) {
        return createProxy(manager::currentSnapshot, prefix, type, false);
    }

    /**
     * 创建“快照锚定”模式的代理实例
     *
     * @param fixedSnapshot 被绑定的固定快照实例
     * @param prefix        配置键前缀
     * @param type          目标类型（接口或类）
     * @param <T>           强类型
     * @return 代理对象实例
     */
    public <T> T createPinnedProxy(ConfigSnapshot fixedSnapshot, String prefix, Class<T> type) {
        return createProxy(() -> fixedSnapshot, prefix, type, true);
    }

    /**
     * 统一代理实例构建逻辑
     */
    public <T> T createProxy(Supplier<ConfigSnapshot> snapshotProvider,
                             String prefix, Class<T> type,
                             boolean isPinned) {
        ConfigMethodInterceptor interceptor = new ConfigMethodInterceptor(
                type,
                prefix,
                snapshotProvider,
                isPinned,
                this,
                converterRegistry
        );

        return ProxyBuilder.forClass(type)
                .withInterfaces(SnapshotAware.class)
                .asEmptyObject()
                .addInterceptor(interceptor)
                .build();
    }
}
