package com.team4u.framework.config.test;

import com.team4u.framework.config.core.spi.ConfigSource;
import org.junit.Assert;
import org.junit.Test;

/**
 * TestConfigContext 单元测试
 */
public class TestConfigContextTest {

    @Test
    public void testCreate() {
        TestConfigContext context = TestConfigContext.create();
        Assert.assertNotNull(context.getManager());
        Assert.assertNotNull(context.getSource());
        Assert.assertEquals("test-mock-source", context.getSource().name());
        Assert.assertEquals(0, context.getSource().priority());
    }

    @Test
    public void testPut() {
        TestConfigContext context = TestConfigContext.create();
        context.put("test.key", "test.value");

        Assert.assertEquals("test.value", context.getManager().getString("test.key").orElse(null));
    }

    @Test
    public void testDelete() {
        TestConfigContext context = TestConfigContext.create();
        context.put("test.key", "test.value");
        Assert.assertEquals("test.value", context.getManager().getString("test.key").orElse(null));

        // delete 使用 Tombstone 语义
        context.delete("test.key");
        Assert.assertFalse(context.getManager().getString("test.key").isPresent());

        // 验证底层存储确实包含 Tombstone
        Assert.assertEquals(ConfigSource.TOMBSTONE_VALUE, context.getSource().load().get("test.key").getValue());
    }

    @Test
    public void testRemove() {
        TestConfigContext context = TestConfigContext.create();
        context.put("test.key", "test.value");
        Assert.assertEquals("test.value", context.getManager().getString("test.key").orElse(null));

        // remove 物理移除
        context.remove("test.key");
        Assert.assertFalse(context.getManager().getString("test.key").isPresent());

        // 验证底层存储不包含该键
        Assert.assertFalse(context.getSource().load().containsKey("test.key"));
    }

    @Test
    public void testCreateProxy() {
        TestConfigContext context = TestConfigContext.create();
        context.put("app.name", "team4u");

        AppConfig config = context.createProxy("app", AppConfig.class);
        Assert.assertEquals("team4u", config.getName());

        // 测试热重载
        context.put("app.name", "new-team4u");
        Assert.assertEquals("new-team4u", config.getName());
    }

    @Test
    public void testDestroy() {
        TestConfigContext context = TestConfigContext.create();
        // 主要是为了覆盖代码，调用不报错即可
        context.destroy();
    }

    /**
     * 测试用的配置 Bean
     */
    public static class AppConfig {
        private String name;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }
}
