package com.team4u.framework.config.core.proxy;

import com.team4u.framework.config.core.ConfigManager;
import com.team4u.framework.config.core.convert.PropertyConverterRegistry;
import com.team4u.framework.config.core.domain.ConfigSnapshot;

import java.lang.reflect.Proxy;
import java.util.function.Supplier;

/**
 * 动态代理工厂
 * <p>
 * 构建代理接口实例对象以提供方法级别的自动感知取值。
 */
public class ConfigProxyFactory {

    private final PropertyConverterRegistry converterRegistry;

    public ConfigProxyFactory(PropertyConverterRegistry converterRegistry) {
        this.converterRegistry = converterRegistry;
    }

    /**
     * 创建 Live Mode 的动态代理实例
     * <p>
     * 每次方法调用时实时从 {@link ConfigManager} 拉取最新的 Snapshot。
     *
     * @param manager 全局门面
     * @param prefix  前缀
     * @param type    接口类型
     * @param <T>     泛型
     * @return 代理对象
     */
    public <T> T createLiveProxy(ConfigManager manager, String prefix, Class<T> type) {
        return createProxy(manager::currentSnapshot, prefix, type, false);
    }

    /**
     * 创建 Pinned Mode (快照锚定模式) 的代理实例
     * <p>
     * 使用给定的固定配置快照进行内部求值，后续不再随 Manager 热更新刷新。
     *
     * @param fixedSnapshot 被钉住的绝对版本快照
     * @param prefix        前缀
     * @param type          接口类型
     * @param <T>           泛型
     * @return 代理对象
     */
    public <T> T createPinnedProxy(ConfigSnapshot fixedSnapshot, String prefix, Class<T> type) {
        return createProxy(() -> fixedSnapshot, prefix, type, true);
    }

    /**
     * 统一创建动态代理实例的辅助方法
     *
     * @param snapshotProvider 快照提供者
     * @param prefix           前缀
     * @param type             接口类型
     * @param isPinned         是否钉住快照
     * @param <T>              泛型
     * @return 代理对象
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
