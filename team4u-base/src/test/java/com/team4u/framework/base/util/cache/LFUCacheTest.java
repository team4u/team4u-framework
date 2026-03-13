package com.team4u.framework.base.util.cache;

import org.junit.Assert;
import org.junit.Test;

/**
 * LFUCache 单元测试
 */
public class LFUCacheTest {

    @Test
    public void testLfuStrategy() {
        LFUCache<String, Integer> cache = new LFUCache<>(2);

        cache.put("a", 1);
        cache.put("b", 2);
        cache.get("a");
        cache.put("c", 3);

        Assert.assertEquals(Integer.valueOf(1), cache.get("a"));
        Assert.assertNull("b 应该作为最低频次项被淘汰", cache.get("b"));
        Assert.assertEquals(Integer.valueOf(3), cache.get("c"));
        Assert.assertEquals(2, cache.size());
    }

    @Test
    public void testTieBreakByRecencyWithinSameFrequency() {
        LFUCache<String, Integer> cache = new LFUCache<>(2);

        cache.put("a", 1);
        cache.put("b", 2);
        cache.put("c", 3);

        Assert.assertNull("同频次时应淘汰更早进入桶的 a", cache.get("a"));
        Assert.assertEquals(Integer.valueOf(2), cache.get("b"));
        Assert.assertEquals(Integer.valueOf(3), cache.get("c"));
    }

    @Test
    public void testBasicOps() {
        LFUCache<String, String> cache = new LFUCache<>(2);
        cache.put("k1", "v1");
        Assert.assertEquals("v1", cache.get("k1"));

        cache.remove("k1");
        Assert.assertNull(cache.get("k1"));

        cache.put("k2", "v2");
        cache.clear();
        Assert.assertEquals(0, cache.size());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testRejectInvalidCapacity() {
        new LFUCache<String, String>(0);
    }
}
