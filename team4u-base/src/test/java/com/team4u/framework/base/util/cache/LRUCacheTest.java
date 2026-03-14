package com.team4u.framework.base.util.cache;

import com.team4u.framework.base.cache.LRUCache;
import org.junit.Assert;
import org.junit.Test;

/**
 * LRUCache 单元测试
 *
 * @author jay.wu
 */
public class LRUCacheTest {

    @Test
    public void testLruStrategy() {
        // 创建容量为 2 的缓存
        LRUCache<String, Integer> cache = new LRUCache<>(2);

        cache.put("a", 1);
        cache.put("b", 2);
        // 访问 "a" 使其变为最近使用
        cache.get("a");
        // 插入 "c"，触发移除最久未使用的 "b"
        cache.put("c", 3);

        Assert.assertEquals("a 应该存在", Integer.valueOf(1), cache.get("a"));
        Assert.assertEquals("c 应该存在", Integer.valueOf(3), cache.get("c"));
        Assert.assertNull("b 应该被移除", cache.get("b"));
        Assert.assertEquals("缓存大小应等于容量", 2, cache.size());
    }

    @Test
    public void testBasicOps() {
        LRUCache<String, String> cache = new LRUCache<>(10);
        cache.put("k1", "v1");
        Assert.assertEquals("读取值不正确", "v1", cache.get("k1"));

        cache.remove("k1");
        Assert.assertNull("移除后不应存在", cache.get("k1"));

        cache.put("k2", "v2");
        cache.clear();
        Assert.assertEquals("清空后大小应为 0", 0, cache.size());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testRejectInvalidCapacity() {
        new LRUCache<String, String>(0);
    }
}
