package com.team4u.framework.base.util;

import org.junit.Assert;
import org.junit.Test;

/**
 * StringUtil 单元测试
 *
 * @author jay.wu
 */
public class StringUtilTest {

    @Test
    public void testIsEmpty() {
        Assert.assertTrue("null 应判定为空", StringUtil.isEmpty(null));
        Assert.assertTrue("空字符串应判定为空", StringUtil.isEmpty(""));
        Assert.assertFalse("非空字符串不应判定为空", StringUtil.isEmpty(" "));
        Assert.assertFalse("非空字符串不应判定为空", StringUtil.isEmpty("abc"));
    }

    @Test
    public void testIsNotEmpty() {
        Assert.assertFalse("null 不应判定为非空", StringUtil.isNotEmpty(null));
        Assert.assertFalse("空字符串不应判定为非空", StringUtil.isNotEmpty(""));
        Assert.assertTrue("空格字符串应判定为非空", StringUtil.isNotEmpty(" "));
        Assert.assertTrue("非空字符串应判定为非空", StringUtil.isNotEmpty("abc"));
    }

    @Test
    public void testIsBlank() {
        Assert.assertTrue("null 应判定为空白", StringUtil.isBlank(null));
        Assert.assertTrue("空字符串应判定为空白", StringUtil.isBlank(""));
        Assert.assertTrue("全空格字符串应判定为空白", StringUtil.isBlank("   "));
        Assert.assertTrue("含制表符字符串应判定为空白", StringUtil.isBlank("\t\n"));
        Assert.assertFalse("非空白字符串不应判定为空白", StringUtil.isBlank(" abc "));
    }

    @Test
    public void testIsNotBlank() {
        Assert.assertFalse("null 不应判定为非空白", StringUtil.isNotBlank(null));
        Assert.assertFalse("全空格字符串不应判定为非空白", StringUtil.isNotBlank("  "));
        Assert.assertTrue("非空白字符串应判定为非空白", StringUtil.isNotBlank(" a "));
    }

    @Test
    public void testSimpleFormat() {
        // 正常格式化
        Assert.assertEquals("格式化结果不匹配", "Hello world", StringUtil.simpleFormat("Hello {}", "world"));
        // 多个参数
        Assert.assertEquals("多参数格式化结果不匹配", "a=1, b=2", StringUtil.simpleFormat("a={}, b={}", 1, 2));
        // 参数为 null 时会拼接 "null"
        Assert.assertEquals("参数为 null 时应拼接 null 字符串", "a=null, b=2", StringUtil.simpleFormat("a={}, b={}", null, 2));
        // 参数过多
        Assert.assertEquals("参数过多时应忽略冗余参数", "a=1", StringUtil.simpleFormat("a={}", 1, 2));
        // 模板为空
        Assert.assertNull("模板为 null 应返回 null", StringUtil.simpleFormat(null, "any"));
        Assert.assertEquals("模板为空串应返回空串", "", StringUtil.simpleFormat("", "any"));
    }

    @Test
    public void testLowerFirst() {
        Assert.assertEquals("首字母转小写不正确", "abc", StringUtil.lowerFirst("Abc"));
        Assert.assertEquals("首字母本就是小写时应保持不变", "abc", StringUtil.lowerFirst("abc"));
        Assert.assertEquals("空字符串首字母转小写应返回原值", "", StringUtil.lowerFirst(""));
        Assert.assertNull("null 首字母转小写应返回 null", StringUtil.lowerFirst(null));
    }

    @Test
    public void testSubBefore() {
        String str = "java.lang.String";
        // indexOf 找到的是第一个点
        Assert.assertEquals("截取第一个分隔符之前内容不正确", "java", StringUtil.subBefore(str, "."));
        Assert.assertEquals("包含分隔符截取不正确", "java.", StringUtil.subBefore(str, ".", true));
        Assert.assertEquals("分隔符不存在时应返回原串", str, StringUtil.subBefore(str, "#"));
        Assert.assertEquals("原串为空应返回原值", "", StringUtil.subBefore("", "."));
        Assert.assertNull("原串为 null 应返回 null", StringUtil.subBefore(null, "."));
    }

    @Test
    public void testSubAfter() {
        String str = "java.lang.String";
        Assert.assertEquals("截取分隔符之后内容不正确", "lang.String", StringUtil.subAfter(str, "."));
        Assert.assertEquals("包含分隔符截取不正确", ".lang.String", StringUtil.subAfter(str, ".", true));
        Assert.assertEquals("分隔符不存在时应返回空串", "", StringUtil.subAfter(str, "#"));
        Assert.assertEquals("原串为空应返回原串", "", StringUtil.subAfter("", "."));
    }

    @Test
    public void testEquals() {
        Assert.assertTrue("两个 null 应相等", StringUtil.equals(null, null));
        Assert.assertFalse("一边为 null 应不相等", StringUtil.equals("a", null));
        Assert.assertTrue("内容相同应相等", StringUtil.equals("abc", new StringBuilder("abc")));
        Assert.assertFalse("内容不同不应相等", StringUtil.equals("abc", "ABC"));
    }

    @Test
    public void testContains() {
        Assert.assertTrue("包含子串应返回 true", StringUtil.contains("hello world", "world"));
        Assert.assertFalse("不包含子串应返回 false", StringUtil.contains("hello", "world"));
        Assert.assertFalse("主串为 null 应返回 false", StringUtil.contains(null, "a"));
        Assert.assertFalse("子串为 null 应返回 false", StringUtil.contains("a", null));
    }

    @Test
    public void testIsWrap() {
        Assert.assertTrue("匹配前后缀应返回 true", StringUtil.isWrap("[hello]", "[", "]"));
        Assert.assertFalse("前缀不匹配应返回 false", StringUtil.isWrap("[hello]", "{", "]"));
        Assert.assertFalse("后缀不匹配应返回 false", StringUtil.isWrap("[hello]", "[", "}"));
        Assert.assertFalse("任意参数为 null 应返回 false", StringUtil.isWrap(null, "[", "]"));
    }

    @Test
    public void testCompareVersion() {
        Assert.assertEquals("版本相等应返回 0", 0, StringUtil.compareVersion("1.0.0", "1.0"));
        Assert.assertEquals("第一个版本大应返回 1", 1, StringUtil.compareVersion("1.0.1", "1.0.0"));
        Assert.assertEquals("第二个版本大应返回 -1", -1, StringUtil.compareVersion("1.0.0", "1.1"));
        Assert.assertEquals("处理前导 0 应正确", 0, StringUtil.compareVersion("1.02", "1.2"));
        Assert.assertEquals("非数字版本应字典序比较", 1, StringUtil.compareVersion("1.b", "1.a"));
        Assert.assertEquals("null 比较应符合逻辑", -1, StringUtil.compareVersion(null, "1.0"));
        Assert.assertEquals("null 比较应符合逻辑", 1, StringUtil.compareVersion("1.0", null));
        Assert.assertEquals("null 比较应符合逻辑", 0, StringUtil.compareVersion(null, null));
    }
}
