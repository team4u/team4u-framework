package com.team4u.framework.base.util;

import com.team4u.framework.base.util.cache.Cache;
import com.team4u.framework.base.util.cache.LFUCache;
import com.team4u.framework.base.util.cache.LRUCache;
import com.team4u.framework.base.util.cache.TimedCache;
import org.junit.Assert;
import org.junit.Test;

/**
 * 缓存工具类单元测试
 *
 * @author jay.wu
 */
public class CacheUtilTest {

    @Test
    public void newLRUCache() {
        Cache<String, String> cache = CacheUtil.newLRUCache(10);
        Assert.assertTrue("生成的缓存应为 LRUCache 实例", cache instanceof LRUCache);
    }

    @Test
    public void newLFUCache() {
        Cache<String, String> cache = CacheUtil.newLFUCache(10);
        Assert.assertTrue("生成的缓存应为 LFUCache 实例", cache instanceof LFUCache);
    }

    @Test
    public void newTimedCache() {
        TimedCache<String, String> cache = CacheUtil.newTimedCache(1000L);
        Assert.assertNotNull("生成的超时缓存不应为空", cache);
    }
}
