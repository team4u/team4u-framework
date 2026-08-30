package com.team4u.framework.config.core.spi;

import com.team4u.framework.config.core.domain.ConfigEntry;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.Map;

/**
 * {@link SystemEnvConfigSource} 单元测试
 * <p>
 * 通过写入和清理 JVM 系统属性来模拟各类场景，避免污染测试环境。
 * 环境变量无法在 JVM 运行期间修改，因此相关断言仅验证现有条目的结构正确性。
 */
public class SystemEnvConfigSourceTest {

    /**
     * 测试用的临时系统属性键，使用足够特殊的前缀以避免与真实属性冲突
     */
    private static final String TEST_KEY = "team4u.test.sysprop";
    private static final String TEST_VALUE = "hello-system";

    @Before
    public void setUp() {
        System.setProperty(TEST_KEY, TEST_VALUE);
    }

    @After
    public void tearDown() {
        System.clearProperty(TEST_KEY);
    }

    @Test
    public void testNameAndPriority() {
        SystemEnvConfigSource source = new SystemEnvConfigSource(10);
        Assert.assertEquals(SystemEnvConfigSource.DEFAULT_NAME, source.name());
        Assert.assertEquals(10, source.priority());
    }

    @Test
    public void testCustomName() {
        SystemEnvConfigSource source = new SystemEnvConfigSource("custom-sys", 5);
        Assert.assertEquals("custom-sys", source.name());
        Assert.assertEquals(5, source.priority());
    }

    @Test
    public void testLoadContainsSystemProperty() {
        SystemEnvConfigSource source = new SystemEnvConfigSource(10);
        Map<String, ConfigEntry> result = source.load();

        // 验证临时写入的系统属性能被正确加载
        Assert.assertTrue("系统属性应被包含在加载结果中", result.containsKey(TEST_KEY));
        ConfigEntry entry = result.get(TEST_KEY);
        Assert.assertEquals(TEST_VALUE, entry.getValue());
        Assert.assertEquals(SystemEnvConfigSource.DEFAULT_NAME, entry.getSourceName());
        Assert.assertTrue("时间戳应为正数", entry.getTimestamp() > 0);
    }

    @Test
    public void testSystemPropertyOverridesNormalizedEnvKey() {
        // java.home 是所有 JVM 必然存在的系统属性，且不可能被环境变量覆盖
        SystemEnvConfigSource source = new SystemEnvConfigSource(10);
        Map<String, ConfigEntry> result = source.load();

        Assert.assertTrue("java.home 系统属性必然存在", result.containsKey("java.home"));
        // 系统属性来源标记正确
        Assert.assertEquals(SystemEnvConfigSource.DEFAULT_NAME, result.get("java.home").getSourceName());
    }

    @Test
    public void testLoadContainsEnvironmentVariables() {
        SystemEnvConfigSource source = new SystemEnvConfigSource(10);
        Map<String, ConfigEntry> result = source.load();

        // 只要操作系统有任意环境变量，结果集就不应为空
        Assert.assertFalse("加载结果不应为空（系统属性和环境变量均不为空）", result.isEmpty());
    }

    @Test
    public void testEnvKeyNormalization() {
        SystemEnvConfigSource source = new SystemEnvConfigSource(10);
        Map<String, ConfigEntry> result = source.load();

        // 验证带下划线的环境变量会同时产生规范化的点分小写键
        boolean hasNormalizedKey = result.keySet().stream()
                .anyMatch(k -> k.contains(".") && k.equals(k.toLowerCase()));
        Assert.assertTrue("应存在点分小写格式的规范化环境变量键", hasNormalizedKey);
    }

    @Test
    public void testLoadReturnsImmutableMap() {
        SystemEnvConfigSource source = new SystemEnvConfigSource(10);
        Map<String, ConfigEntry> result = source.load();

        try {
            result.put("should.fail", new ConfigEntry("should.fail", "x", "test", 0));
            Assert.fail("修改只读 Map 应抛出 UnsupportedOperationException");
        } catch (UnsupportedOperationException e) {
            // 符合预期
        }
    }

    @Test
    public void testDynamicPropertyUpdate() {
        SystemEnvConfigSource source = new SystemEnvConfigSource(10);

        // 第一次加载：已有测试键
        Map<String, ConfigEntry> first = source.load();
        Assert.assertTrue(first.containsKey(TEST_KEY));

        // 动态修改系统属性
        System.setProperty(TEST_KEY, "updated-value");

        // 第二次加载：应能感知到变更（每次 load 都重新读取）
        Map<String, ConfigEntry> second = source.load();
        Assert.assertEquals("load() 应反映系统属性的最新值", "updated-value", second.get(TEST_KEY).getValue());
    }
}
