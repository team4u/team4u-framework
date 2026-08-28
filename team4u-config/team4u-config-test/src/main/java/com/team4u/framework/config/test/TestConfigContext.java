package com.team4u.framework.config.test;

import com.team4u.framework.config.core.ConfigManager;
import com.team4u.framework.config.core.ConfigProxyContext;
import com.team4u.framework.config.core.internal.DefaultConfigManager;
import com.team4u.framework.config.core.proxy.ConfigProxyFactory;
import com.team4u.framework.config.core.spi.InMemoryConfigSource;
import lombok.Getter;

/**
 * 配置管理测试上下文工具类
 * <p>
 * 封装了零延迟热重载的 ConfigManager 及 InMemoryConfigSource，专门用于加速和简化单元测试。
 * </p>
 */
@Getter
public class TestConfigContext {

    private final InMemoryConfigSource source;
    private final ConfigManager configManager;

    /**
     * 快捷创建默认配置的测试上下文
     */
    public static TestConfigContext create() {
        return new TestConfigContext("test-mock-source", 0);
    }

    public TestConfigContext(String sourceName, int priority) {
        this.source = new InMemoryConfigSource(sourceName, priority);
        // Task 9 拆分前显式保留测试工具的代理能力；0 延时实现同步热重载
        this.configManager = ConfigManager.builder()
                .addSource(source)
                .addWatcher(source)
                .debounceWindow(0)
                .proxyCreator(TestConfigContext::createConfigProxy)
                .build();
    }

    /**
     * 写入配置并立即触发同步热重载
     */
    public TestConfigContext put(String key, String value) {
        source.putAndRefresh(key, value);
        return this;
    }

    /**
     * 标记配置为失效并立即触发同步热重载 (Tombstone 语义)
     */
    public TestConfigContext delete(String key) {
        source.delete(key);
        source.fireChange();
        return this;
    }

    /**
     * 彻底移除配置并立即触发同步热重载
     */
    public TestConfigContext remove(String key) {
        source.remove(key);
        source.fireChange();
        return this;
    }

    /**
     * 快捷生成代理对象
     */
    public <T> T createProxy(String prefix, Class<T> configType) {
        return configManager.createProxy(prefix, configType);
    }

    private static <T> T createConfigProxy(ConfigProxyContext context, String prefix, Class<T> configType) {
        return new ConfigProxyFactory(context.converterRegistry())
                .createLiveProxy(context.manager(), prefix, configType);
    }

    /**
     * 快捷销毁资源
     */
    public void destroy() {
        if (configManager instanceof DefaultConfigManager) {
            ((DefaultConfigManager) configManager).destroy();
        }
    }
}