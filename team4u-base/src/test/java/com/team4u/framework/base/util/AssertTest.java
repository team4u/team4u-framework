package com.team4u.framework.base.util;

import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 断言工具类单元测试
 *
 * @author jay.wu
 */
public class AssertTest {

    @Test
    public void isTrue() {
        // 测试表达式为 true
        Assert.isTrue(true, "应通过校验");

        // 测试表达式为 false
        try {
            Assert.isTrue(false, "校验失败消息");
            org.junit.Assert.fail("表达式为 false 时应抛出 IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            org.junit.Assert.assertEquals("校验失败消息", e.getMessage());
        }
    }

    @Test
    public void notNull() {
        // 测试对象不为 null
        Assert.notNull(new Object(), "应通过校验");

        // 测试对象为 null
        try {
            Assert.notNull(null, "对象不能为空");
            org.junit.Assert.fail("对象为 null 时应抛出 IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            org.junit.Assert.assertEquals("对象不能为空", e.getMessage());
        }
    }

    @Test
    public void notBlank() {
        // 测试字符串不为空白
        Assert.notBlank("test", "应通过校验");

        // 测试字符串为 null
        try {
            Assert.notBlank(null, "字符串不能为空白");
            org.junit.Assert.fail("字符串为 null 时应抛出 IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            org.junit.Assert.assertEquals("字符串不能为空白", e.getMessage());
        }

        // 测试字符串为空字符串
        try {
            Assert.notBlank("", "字符串不能为空白");
            org.junit.Assert.fail("字符串为空字符串时应抛出 IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            org.junit.Assert.assertEquals("字符串不能为空白", e.getMessage());
        }

        // 测试字符串为全空格
        try {
            Assert.notBlank("   ", "字符串不能为空白");
            org.junit.Assert.fail("字符串为全空格时应抛出 IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            org.junit.Assert.assertEquals("字符串不能为空白", e.getMessage());
        }
    }

    @Test
    public void notEmptyCollection() {
        // 测试集合不为空
        Assert.notEmpty(Collections.singletonList("test"), "应通过校验");

        // 测试集合为 null
        try {
            Assert.notEmpty((java.util.Collection<?>) null, "集合不能为空");
            org.junit.Assert.fail("集合为 null 时应抛出 IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            org.junit.Assert.assertEquals("集合不能为空", e.getMessage());
        }

        // 测试集合为空
        try {
            Assert.notEmpty(Collections.emptyList(), "集合不能为空");
            org.junit.Assert.fail("集合为空时应抛出 IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            org.junit.Assert.assertEquals("集合不能为空", e.getMessage());
        }
    }

    @Test
    public void notEmptyMap() {
        // 测试 Map 不为空
        Map<String, String> map = new HashMap<>();
        map.put("key", "value");
        Assert.notEmpty(map, "应通过校验");

        // 测试 Map 为 null
        try {
            Assert.notEmpty((Map<?, ?>) null, "Map 不能为空");
            org.junit.Assert.fail("Map 为 null 时应抛出 IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            org.junit.Assert.assertEquals("Map 不能为空", e.getMessage());
        }

        // 测试 Map 为空
        try {
            Assert.notEmpty(Collections.emptyMap(), "Map 不能为空");
            org.junit.Assert.fail("Map 为空时应抛出 IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            org.junit.Assert.assertEquals("Map 不能为空", e.getMessage());
        }
    }
}
