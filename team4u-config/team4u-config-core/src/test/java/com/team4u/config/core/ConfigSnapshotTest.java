package com.team4u.config.core;

import com.team4u.config.core.domain.ConfigEntry;
import com.team4u.config.core.domain.ConfigSnapshot;
import org.junit.Assert;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

public class ConfigSnapshotTest {

    @Test
    public void testGetAndPrefix() {
        Map<String, ConfigEntry> entries = new HashMap<>();
        long now = System.currentTimeMillis();
        entries.put("app.name", new ConfigEntry("app.name", "test", "src", now));
        entries.put("db.mysql.url", new ConfigEntry("db.mysql.url", "jdbc:mysql://localhost", "src", now));
        entries.put("db.mysql.user", new ConfigEntry("db.mysql.user", "root", "src", now));
        // 已删除的数据项 (Tombstone 语义)
        entries.put("db.mysql.pwd", new ConfigEntry("db.mysql.pwd", null, "src", now));

        ConfigSnapshot snapshot = new ConfigSnapshot(1L, entries);

        // 测试精确匹配获取
        Assert.assertEquals("test", snapshot.get("app.name").orElse(null));
        Assert.assertFalse(snapshot.get("not.exist").isPresent());
        Assert.assertFalse(snapshot.get("db.mysql.pwd").isPresent());

        // 测试基于前缀的子集获取
        Map<String, String> dbProps = snapshot.getByPrefix("db.mysql");
        Assert.assertEquals(2, dbProps.size());
        Assert.assertEquals("jdbc:mysql://localhost", dbProps.get("url"));
        Assert.assertEquals("root", dbProps.get("user"));
        Assert.assertNull("已删除的项不应出现在前缀搜索结果中", dbProps.get("pwd"));
        
        // 测试带有尾部点号的前缀逻辑处理
        Map<String, String> dbPropsWithDot = snapshot.getByPrefix("db.mysql.");
        Assert.assertEquals(2, dbPropsWithDot.size());
        Assert.assertEquals("root", dbPropsWithDot.get("user"));
    }
}
