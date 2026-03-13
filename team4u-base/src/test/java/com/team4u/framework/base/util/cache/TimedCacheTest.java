package com.team4u.framework.base.util.cache;

import com.team4u.framework.base.util.ThreadUtil;
import org.junit.Assert;
import org.junit.Test;

/**
 * TimedCache 单元测试
 *
 * @author jay.wu
 */
public class TimedCacheTest {

    @Test
    public void testExpiration() {
        // 创建超时时间为 10ms 的缓存
        TimedCache<String, String> cache = new TimedCache<>(10);

        cache.put("k1", "v1");
        Assert.assertEquals("过期前应能获取值", "v1", cache.get("k1"));

        // 等待过期
        ThreadUtil.sleep(20);

        Assert.assertNull("过期后不应获取到值", cache.get("k1"));
        Assert.assertEquals("过期后 size 不一定会立即变为 0 (取决于是否触发 get)", 0, cache.size());
    }

    @Test
    public void testNoExpiration() {
        // 设置为永不过期
        TimedCache<String, String> cache = new TimedCache<>(0);
        cache.put("k1", "v1");

        ThreadUtil.sleep(20);
        Assert.assertEquals("永不过期缓存应仍能获取值", "v1", cache.get("k1"));
    }

    @Test
    public void testBasicOps() {
        TimedCache<String, String> cache = new TimedCache<>(1000);
        cache.put("k1", "v1");
        Assert.assertEquals("读取值不正确", "v1", cache.get("k1"));

        cache.remove("k1");
        Assert.assertNull("移除后不应存在", cache.get("k1"));

        cache.put("k2", "v2");
        cache.clear();
        Assert.assertEquals("清空后大小应为 0", 0, cache.size());
    }
}
