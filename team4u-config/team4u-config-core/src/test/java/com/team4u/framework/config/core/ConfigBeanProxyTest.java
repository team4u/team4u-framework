package com.team4u.framework.config.core;

import com.team4u.framework.config.core.annotation.ConfigDefault;
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
                .build();

        // 为普通 Java Bean 创建代理
        AppBean config = manager.createProxy("app", AppBean.class);

        // 1. 验证基本功能
        Assert.assertTrue("应为 SnapshotAware 实例", config instanceof SnapshotAware);
        Assert.assertEquals("old-app", config.getName());
        Assert.assertEquals(8080, config.getPort());

        // 2. 验证实时热更新
        source.putAndRefresh("app.name", "new-app");
        // 等待防抖时间（默认 500ms）
        sleep(800);
        
        Assert.assertEquals("代理应能实时感知配置变更", "new-app", config.getName());

        // 3. 验证快照锚定 (Pinning)
        AppBean pinned = SnapshotAware.pin(config);
        source.putAndRefresh("app.name", "latest-app");
        sleep(800);

        Assert.assertEquals("原始代理应继续更新", "latest-app", config.getName());
        Assert.assertEquals("锚定后的代理应保持旧值", "new-app", pinned.getName());
    }

    @Test
    public void testBeanFieldDefaultValue() {
        InMemoryConfigSource source = new InMemoryConfigSource("test", 1);
        ConfigManager manager = ConfigManager.builder()
                .addSource(source)
                .addWatcher(source) // 启用 watcher 以便 putAndRefresh 生效
                .build();

        AppBeanWithDefault config = manager.createProxy("app", AppBeanWithDefault.class);

        // 1. 没有任何配置和注解，应使用字段初始值
        Assert.assertEquals("field-default", config.getName());
        Assert.assertEquals(9090, config.getPort());

        // 2. 有注解时，注解覆盖字段初始值
        Assert.assertEquals("annotation-default", config.getAnnotationValue());

        // 3. 有配置时，配置覆盖所有
        source.putAndRefresh("app.name", "config-value");
        sleep(800);
        Assert.assertEquals("config-value", config.getName());
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    public static class AppBeanWithDefault {
        private String name = "field-default";
        private int port = 9090;
        private String annotationValue = "field-initial";

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

        @ConfigDefault("annotation-default")
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
