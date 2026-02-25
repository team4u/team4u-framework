package com.team4u.framework.config.core.spi;

import com.team4u.framework.config.core.domain.ConfigEntry;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * {@link InMemoryConfigSource} 单元测试
 */
public class InMemoryConfigSourceTest {

    private InMemoryConfigSource source;

    @Before
    public void setUp() {
        source = new InMemoryConfigSource("mem-test", 10);
    }

    /**
     * 验证数据源元信息
     */
    @Test
    public void testNameAndPriority() {
        Assert.assertEquals("mem-test", source.name());
        Assert.assertEquals(10, source.priority());
    }

    /**
     * 验证写入后可通过 load() 全量读取
     */
    @Test
    public void testPutAndLoad() {
        source.put("app.name", "team4u");
        source.put("app.port", "8080");

        Map<String, ConfigEntry> result = source.load();

        Assert.assertEquals(2, result.size());
        Assert.assertEquals("team4u", result.get("app.name").getValue());
        Assert.assertEquals("8080", result.get("app.port").getValue());
    }

    /**
     * 验证 putAll 批量写入
     */
    @Test
    public void testPutAll() {
        Map<String, String> batch = new HashMap<>();
        batch.put("k1", "v1");
        batch.put("k2", "v2");
        source.putAll(batch);

        Map<String, ConfigEntry> result = source.load();
        Assert.assertEquals(2, result.size());
        Assert.assertEquals("v1", result.get("k1").getValue());
        Assert.assertEquals("v2", result.get("k2").getValue());
    }

    /**
     * 验证 delete() 将配置项标记为 Tombstone（值为 {@link ConfigSource#TOMBSTONE_VALUE}），
     * 而非从存储中抹除，使聚合层能感知到删除信号
     */
    @Test
    public void testDeleteCreatesTombstone() {
        source.put("app.debug", "true");
        source.delete("app.debug");

        Map<String, ConfigEntry> result = source.load();
        // 条目仍然存在，但值为 {@link ConfigSource#TOMBSTONE_VALUE}，表明这是 Tombstone
        Assert.assertTrue(result.containsKey("app.debug"));
        Assert.assertEquals(ConfigSource.TOMBSTONE_VALUE, result.get("app.debug").getValue());
        Assert.assertTrue(result.get("app.debug").isEmptyOrDeleted());
    }

    /**
     * 验证 remove() 将配置项从内存中彻底抹去，
     * 与 delete() 不同，低优先级数据源中的同名键将重新生效
     */
    @Test
    public void testRemoveErasesEntry() {
        source.put("app.debug", "true");
        source.remove("app.debug");

        Map<String, ConfigEntry> result = source.load();
        Assert.assertFalse(result.containsKey("app.debug"));
    }

    /**
     * 验证 clear() 清空所有配置项
     */
    @Test
    public void testClear() {
        source.put("a", "1");
        source.put("b", "2");
        source.clear();

        Assert.assertEquals(0, source.size());
        Assert.assertTrue(source.load().isEmpty());
    }

    /**
     * 验证 loadSince() 增量加载：仅返回时间戳严格大于给定值的条目
     */
    @Test
    public void testLoadSinceReturnOnlyChangedEntries() throws InterruptedException {
        // 写入第一批数据，记录此时时间戳
        source.put("stable", "v1");
        long checkpoint = System.currentTimeMillis();

        // 保证后续写入在 checkpoint 之后
        Thread.sleep(5);

        // 写入第二批数据
        source.put("changed", "v2");

        Map<String, ConfigEntry> delta = source.loadSince(checkpoint);

        // 只有 changed 是在 checkpoint 之后写入的
        Assert.assertEquals(1, delta.size());
        Assert.assertTrue(delta.containsKey("changed"));
        Assert.assertEquals("v2", delta.get("changed").getValue());
    }

    /**
     * 验证全量 load() 返回的是快照，对其修改不影响内部存储
     */
    @Test
    public void testLoadReturnsSnapshot() {
        source.put("x", "1");
        Map<String, ConfigEntry> snapshot = source.load();

        // 尝试修改快照（应抛出 UnsupportedOperationException）
        try {
            snapshot.put("y", new ConfigEntry("y", "2", "mem-test", 0));
            Assert.fail("期望快照不可变");
        } catch (UnsupportedOperationException e) {
            // 符合预期
        }

        // 内部数据不受影响
        Assert.assertEquals(1, source.size());
    }

    /**
     * 验证 ConfigEntry 中的 sourceName 字段与数据源名称一致
     */
    @Test
    public void testEntrySourceName() {
        source.put("env", "prod");
        ConfigEntry entry = source.load().get("env");
        Assert.assertEquals("mem-test", entry.getSourceName());
    }

    /**
     * 验证 watch() 注入回调后，fireChange() 能够触发该回调
     */
    @Test
    public void testWatchAndFireChange() {
        // 记录回调被触发的次数
        int[] callCount = {0};
        source.watch(() -> callCount[0]++);

        source.fireChange();
        source.fireChange();

        Assert.assertEquals(2, callCount[0]);
    }

    /**
     * 验证 putAndRefresh() 写入配置后自动触发一次变更信号
     */
    @Test
    public void testPutAndRefreshTriggersSignal() {
        int[] callCount = {0};
        source.watch(() -> callCount[0]++);

        source.putAndRefresh("feature.flag", "true");

        // 数据已写入
        Assert.assertEquals("true", source.load().get("feature.flag").getValue());
        // 变更信号恰好触发一次
        Assert.assertEquals(1, callCount[0]);
    }

    /**
     * 验证 putAllAndRefresh() 批量写入后只统一触发一次变更信号
     */
    @Test
    public void testPutAllAndRefreshTriggersSignalOnce() {
        int[] callCount = {0};
        source.watch(() -> callCount[0]++);

        Map<String, String> batch = new HashMap<>();
        batch.put("a", "1");
        batch.put("b", "2");
        batch.put("c", "3");
        source.putAllAndRefresh(batch);

        // 三条数据均已写入
        Assert.assertEquals(3, source.size());
        // 无论写入多少条，变更信号只触发一次
        Assert.assertEquals(1, callCount[0]);
    }
}
