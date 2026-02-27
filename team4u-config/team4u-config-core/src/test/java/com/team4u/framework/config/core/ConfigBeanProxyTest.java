package com.team4u.framework.config.core;

import com.team4u.framework.config.core.proxy.SnapshotAware;
import com.team4u.framework.config.core.spi.InMemoryConfigSource;
import org.junit.Assert;
import org.junit.Test;

/**
 * 验证 Java Bean 实时代理功能的单元测试
 */
public class ConfigBeanProxyTest {

    @Test
    public void testLiveBeanProxy() {
        InMemoryConfigSource source = new InMemoryConfigSource("test", 1);
        source.put("app.name", "old-app");
        source.put("app.port", "8080");

        ConfigManager manager = ConfigManager.builder()
                .addSource(source)
                .addWatcher(source)
                .debounceWindow(0)
                .build();

        // 为普通 Java Bean 创建代理
        AppBean config = manager.createProxy("app", AppBean.class);

        // 验证基本功能
        Assert.assertTrue("应为 SnapshotAware 实例", config instanceof SnapshotAware);
        Assert.assertEquals("old-app", config.getName());
        Assert.assertEquals(8080, config.getPort());

        // 验证实时热更新
        source.putAndRefresh("app.name", "new-app");
        Assert.assertEquals("代理应能实时感知配置变更", "new-app", config.getName());

        // 验证快照锚定 (Pinning)：锚定后代理值不再更新
        AppBean pinned = SnapshotAware.pin(config);
        source.putAndRefresh("app.name", "latest-app");

        Assert.assertEquals("原始代理应继续更新", "latest-app", config.getName());
        Assert.assertEquals("锚定后的代理应保持旧值", "new-app", pinned.getName());
    }

    @Test
    public void testBeanFieldDefaultValue() {
        InMemoryConfigSource source = new InMemoryConfigSource("test", 1);
        ConfigManager manager = ConfigManager.builder()
                .addSource(source)
                .addWatcher(source)
                .debounceWindow(0)
                .build();

        AppBeanWithDefault config = manager.createProxy("app", AppBeanWithDefault.class);

        // 没有任何配置，应使用字段初始值
        Assert.assertEquals("field-default", config.getName());
        Assert.assertEquals(9090, config.getPort());

        // 字段初始值也可以通过 @ConfigKey 映射到不同配置项进行验证
        Assert.assertEquals("initial-value", config.getAnnotationValue());

        // 有配置时，配置覆盖所有默认值
        source.putAndRefresh("app.name", "config-value");
        Assert.assertEquals("config-value", config.getName());
    }

    public static class AppBeanWithDefault {
        private String name = "field-default";
        private int port = 9090;
        // 字段初始值就是天然的默认值
        private String annotationValue = "initial-value";

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public String getAnnotationValue() {
            return annotationValue;
        }

        public void setAnnotationValue(String annotationValue) {
            this.annotationValue = annotationValue;
        }
    }

    /**
     * 测试用的普通 Java Bean
     */
    public static class AppBean {
        private String name;
        private int port;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }
    }
}
