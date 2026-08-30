package com.team4u.framework.base.util;

import org.junit.Assert;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * MapUtil 单元测试
 *
 * @author jay.wu
 */
public class MapUtilTest {

    @Test
    public void isEmpty() {
        Assert.assertTrue("null Map 应判定为空", MapUtil.isEmpty(null));
        Assert.assertTrue("空 Map 应判定为空", MapUtil.isEmpty(new HashMap<>()));

        Map<String, String> map = new HashMap<>();
        map.put("key", "value");
        Assert.assertFalse("非空 Map 不应判定为空", MapUtil.isEmpty(map));
    }

    @Test
    public void isNotEmpty() {
        Assert.assertFalse("null Map 不应判定为非空", MapUtil.isNotEmpty(null));
        Assert.assertFalse("空 Map 不应判定为非空", MapUtil.isNotEmpty(new HashMap<>()));

        Map<String, String> map = new HashMap<>();
        map.put("key", "value");
        Assert.assertTrue("非空 Map 应判定为非空", MapUtil.isNotEmpty(map));
    }

    @Test
    public void getByPath() {
        Map<String, Object> map = new HashMap<>();
        Map<String, Object> user = new HashMap<>();
        Map<String, Object> address = new HashMap<>();
        address.put("city", "Shanghai");
        user.put("address", address);
        map.put("user", user);

        // 测试正常路径获取
        Assert.assertEquals("Shanghai", MapUtil.getByPath(map, "user.address.city", String.class));

        // 测试中间节点不存在的情况
        Assert.assertNull("节点不存在应返回 null", MapUtil.getByPath(map, "user.profile.name", String.class));

        // 测试最终值为 null 的情况
        address.put("city", null);
        Assert.assertNull("最终值为 null 应返回 null", MapUtil.getByPath(map, "user.address.city", String.class));

        // 测试路径为 null 或空字符串
        Assert.assertNull("路径为 null 应返回 null", MapUtil.getByPath(map, null, String.class));
        Assert.assertNull("路径为空应返回 null", MapUtil.getByPath(map, "", String.class));

        // 测试 Map 为 null
        Assert.assertNull("Map 为 null 应返回 null", MapUtil.getByPath(null, "user.address.city", String.class));

        // 测试类型转换功能
        address.put("code", 123);
        Assert.assertEquals(Integer.valueOf(123), MapUtil.getByPath(map, "user.address.code", Integer.class));
        Assert.assertEquals("123", MapUtil.getByPath(map, "user.address.code", String.class));
    }
}
