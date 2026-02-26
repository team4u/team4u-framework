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
        try { Thread.sleep(800); } catch (InterruptedException ignored) {}
        
        Assert.assertEquals("代理应能实时感知配置变更", "new-app", config.getName());

        // 3. 验证快照锚定 (Pinning)
        AppBean pinned = SnapshotAware.pin(config);
        source.putAndRefresh("app.name", "latest-app");
        try { Thread.sleep(800); } catch (InterruptedException ignored) {}

        Assert.assertEquals("原始代理应继续更新", "latest-app", config.getName());
        Assert.assertEquals("锚定后的代理应保持旧值", "new-app", pinned.getName());
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
