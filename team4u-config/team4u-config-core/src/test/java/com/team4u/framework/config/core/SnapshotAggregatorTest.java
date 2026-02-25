package com.team4u.framework.config.core;

import com.team4u.framework.config.core.domain.ConfigSnapshot;
import com.team4u.framework.config.core.internal.SnapshotAggregator;
import com.team4u.framework.config.core.spi.InMemoryConfigSource;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;

public class SnapshotAggregatorTest {

    @Test
    public void testAggregatePriorityAndTombstone() {
        // 高优先级 = 1
        InMemoryConfigSource highSource = new InMemoryConfigSource("High", 1);
        highSource.put("app.name", "high-name");
        highSource.delete("app.port"); // 模拟删除：在高版本设置 null（Tombstone 语义）

        // 低优先级 = 10
        InMemoryConfigSource lowSource = new InMemoryConfigSource("Low", 10);
        lowSource.put("app.name", "low-name");
        lowSource.put("app.port", "8080");
        lowSource.put("app.env", "dev");

        SnapshotAggregator aggregator = new SnapshotAggregator();
        // 重构后：必须按优先级预先排好序传入（高优先级在前）
        ConfigSnapshot snapshot = aggregator.aggregate(Arrays.asList(highSource, lowSource), 1L);

        // 高优先级的值应当完美覆盖低优先级
        Assert.assertEquals("high-name", snapshot.get("app.name").orElse(null));
        // app.port 被显式删除，即使低优先级有值也不应展现
        Assert.assertFalse(snapshot.get("app.port").isPresent());
        // 其它低优先级特有的键应保留
        Assert.assertEquals("dev", snapshot.get("app.env").orElse(null));
    }

    @Test
    public void testAggregateWithEmptySources() {
        SnapshotAggregator aggregator = new SnapshotAggregator();
        ConfigSnapshot snapshot = aggregator.aggregate(null, 1L);
        Assert.assertTrue(snapshot.getEntries().isEmpty());
        Assert.assertEquals(1L, snapshot.getVersion());
    }
}
