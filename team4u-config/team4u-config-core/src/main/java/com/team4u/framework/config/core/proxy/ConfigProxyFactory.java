package com.team4u.framework.config.core.proxy;

import com.team4u.framework.config.core.ConfigManager;
import com.team4u.framework.config.core.convert.PropertyConverterRegistry;
import com.team4u.framework.config.core.domain.ConfigSnapshot;

import java.lang.reflect.Proxy;
import java.util.function.Supplier;

/**
 * 动态代理工厂
 * <p>
 * 负责构建配置接口的代理实例。
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
     * <p>
     * 每次调用方法时，代理对象都会从 {@link ConfigManager} 拉取当前最新的配置快照。
     * </p>
     *
     * @param manager 全局配置管理门面
     * @param prefix  配置键前缀
     * @param type    目标接口类型
     * @param <T>     接口强类型
     * @return 代理对象实例
     */
    public <T> T createLiveProxy(ConfigManager manager, String prefix, Class<T> type) {
        return createProxy(manager::currentSnapshot, prefix, type, false);
    }

    /**
     * 创建“快照锚定”模式的代理实例
     * <p>
     * 代理对象内部绑定一个固定的配置快照，后续方法调用始终基于该快照执行，
     * 不受全局配置热重载的影响。
     * </p>
     *
     * @param fixedSnapshot 被绑定的固定快照实例
     * @param prefix        配置键前缀
     * @param type          目标接口类型
     * @param <T>           接口强类型
     * @return 代理对象实例
     */
    public <T> T createPinnedProxy(ConfigSnapshot fixedSnapshot, String prefix, Class<T> type) {
        return createProxy(() -> fixedSnapshot, prefix, type, true);
    }

    /**
     * 统一代理实例构建辅助方法
     *
     * @param snapshotProvider 快照提供者函数
     * @param prefix           配置前缀
     * @param type             目标接口类型
     * @param isPinned         是否为固定快照模式
     */
    @SuppressWarnings("unchecked")
    <T> T createProxy(Supplier<ConfigSnapshot> snapshotProvider,
                      String prefix, Class<T> type,
                      boolean isPinned) {
        SnapshotAwareInvocationHandler handler = new SnapshotAwareInvocationHandler(
                type,
                prefix,
                snapshotProvider,
                isPinned,
                this,
                converterRegistry);

        return (T) Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class[]{type, SnapshotAware.class},
                handler);
    }

}
