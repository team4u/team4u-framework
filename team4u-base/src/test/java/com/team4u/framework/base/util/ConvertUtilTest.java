package com.team4u.framework.base.util;

import org.junit.Assert;
import org.junit.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ConvertUtil 单元测试
 *
 * @author jay.wu
 */
public class ConvertUtilTest {

    @Test
    public void testConvert() {
        // 测试转换为 String
        Assert.assertEquals("123", ConvertUtil.convert(String.class, 123));
        Assert.assertEquals("123", ConvertUtil.convert(String.class, 123L));
        Assert.assertNull(ConvertUtil.convert(String.class, null));
        Assert.assertEquals("default", ConvertUtil.convert(String.class, null, "default"));

        // 测试转换为 Long
        Assert.assertEquals(Long.valueOf(123L), ConvertUtil.convert(Long.class, "123"));
        Assert.assertEquals(Long.valueOf(123L), ConvertUtil.convert(long.class, 123));
        Assert.assertNull(ConvertUtil.convert(Long.class, "abc"));
        Assert.assertEquals(Long.valueOf(456L), ConvertUtil.convert(Long.class, "abc", 456L));

        // 测试转换为 Integer
        Assert.assertEquals(Integer.valueOf(123), ConvertUtil.convert(Integer.class, "123"));
        Assert.assertEquals(Integer.valueOf(123), ConvertUtil.convert(int.class, 123L));
        Assert.assertNull(ConvertUtil.convert(Integer.class, "abc"));
        Assert.assertEquals(Integer.valueOf(456), ConvertUtil.convert(Integer.class, "abc", 456));

        // 测试转换为 Double
        Assert.assertEquals(Double.valueOf(123.45), ConvertUtil.convert(Double.class, "123.45"));
        Assert.assertEquals(Double.valueOf(123.0), ConvertUtil.convert(double.class, 123));
        Assert.assertNull(ConvertUtil.convert(Double.class, "abc"));
        Assert.assertEquals(Double.valueOf(456.78), ConvertUtil.convert(Double.class, "abc", 456.78));

        // 测试转换为 BigDecimal
        Assert.assertEquals(new BigDecimal("123.45"), ConvertUtil.convert(BigDecimal.class, "123.45"));
        Assert.assertNull(ConvertUtil.convert(BigDecimal.class, "abc"));

        // 测试转换为 Boolean
        Assert.assertTrue(ConvertUtil.convert(Boolean.class, "true"));
        Assert.assertTrue(ConvertUtil.convert(boolean.class, "1"));
        Assert.assertTrue(ConvertUtil.convert(Boolean.class, "yes"));
        Assert.assertTrue(ConvertUtil.convert(Boolean.class, "ok"));
        Assert.assertTrue(ConvertUtil.convert(Boolean.class, "on"));
        Assert.assertTrue(ConvertUtil.convert(Boolean.class, "y"));
        Assert.assertFalse(ConvertUtil.convert(Boolean.class, "false"));
        Assert.assertFalse(ConvertUtil.convert(Boolean.class, "0"));
        Assert.assertFalse(ConvertUtil.convert(Boolean.class, "no"));
        Assert.assertFalse(ConvertUtil.convert(Boolean.class, "off"));
        Assert.assertFalse(ConvertUtil.convert(Boolean.class, "n"));
        Assert.assertNull(ConvertUtil.convert(Boolean.class, "abc"));

        // 测试转换为 Float
        Assert.assertEquals(Float.valueOf(123.45f), ConvertUtil.convert(Float.class, "123.45"));
        Assert.assertEquals(Float.valueOf(123.0f), ConvertUtil.convert(float.class, 123));

        // 测试转换为 Short
        Assert.assertEquals(Short.valueOf((short) 123), ConvertUtil.convert(Short.class, "123"));
        Assert.assertEquals(Short.valueOf((short) 123), ConvertUtil.convert(short.class, 123));

        // 测试转换为 Byte
        Assert.assertEquals(Byte.valueOf((byte) 123), ConvertUtil.convert(Byte.class, "123"));
        Assert.assertEquals(Byte.valueOf((byte) 123), ConvertUtil.convert(byte.class, 123));

        // 测试转换为 Character
        Assert.assertEquals(Character.valueOf('a'), ConvertUtil.convert(Character.class, "a"));
        Assert.assertEquals(Character.valueOf('1'), ConvertUtil.convert(char.class, 1));

        // 测试转换为数组
        Assert.assertArrayEquals(new String[]{"a", "b", "c"}, ConvertUtil.convert(String[].class, "a, b, c"));
        Assert.assertArrayEquals(new Integer[]{1, 2, 3}, ConvertUtil.convert(Integer[].class, "1, 2, 3"));
        Assert.assertArrayEquals(new int[]{1, 2, 3}, ConvertUtil.convert(int[].class, "1, 2, 3"));

        // 测试转换为 Number
        Assert.assertEquals(new BigDecimal("123.45"), ConvertUtil.convert(Number.class, "123.45"));

        // 测试 Map 转换为 Bean
        Map<String, Object> map = new HashMap<>();
        map.put("name", "test");
        map.put("age", 20);
        TestBean bean = ConvertUtil.convert(TestBean.class, map);
        Assert.assertNotNull(bean);
        Assert.assertEquals("test", bean.getName());
        Assert.assertEquals(20, bean.getAge());
    }

    @Test
    public void testToStr() {
        Assert.assertEquals("123", ConvertUtil.toStr(123));
        Assert.assertNull(ConvertUtil.toStr(null));
        Assert.assertEquals("default", ConvertUtil.toStr(null, "default"));
        Assert.assertEquals("123", ConvertUtil.toStr(123, "default"));
    }

    @Test
    public void testToLong() {
        Assert.assertEquals(Long.valueOf(123L), ConvertUtil.toLong(123L));
        Assert.assertEquals(Long.valueOf(123L), ConvertUtil.toLong(123));
        Assert.assertEquals(Long.valueOf(123L), ConvertUtil.toLong(" 123 "));
        Assert.assertNull(ConvertUtil.toLong("abc"));
        Assert.assertEquals(Long.valueOf(456L), ConvertUtil.toLong("abc", 456L));
        Assert.assertNull(ConvertUtil.toLong(null));
    }

    @Test
    public void testToInt() {
        Assert.assertEquals(Integer.valueOf(123), ConvertUtil.toInt(123));
        Assert.assertEquals(Integer.valueOf(123), ConvertUtil.toInt(123L));
        Assert.assertEquals(Integer.valueOf(123), ConvertUtil.toInt(" 123 "));
        Assert.assertNull(ConvertUtil.toInt("abc"));
        Assert.assertEquals(Integer.valueOf(456), ConvertUtil.toInt("abc", 456));
        Assert.assertNull(ConvertUtil.toInt(null));
    }

    @Test
    public void testToDouble() {
        Assert.assertEquals(Double.valueOf(123.45), ConvertUtil.toDouble(123.45));
        Assert.assertEquals(Double.valueOf(123.0), ConvertUtil.toDouble(123));
        Assert.assertEquals(Double.valueOf(123.45), ConvertUtil.toDouble(" 123.45 "));
        Assert.assertNull(ConvertUtil.toDouble("abc"));
        Assert.assertEquals(Double.valueOf(456.78), ConvertUtil.toDouble("abc", 456.78));
        Assert.assertNull(ConvertUtil.toDouble(null));
    }

    @Test
    public void testToBigDecimal() {
        Assert.assertEquals(new BigDecimal("123.45"), ConvertUtil.toBigDecimal(new BigDecimal("123.45")));
        Assert.assertEquals(new BigDecimal("123.45"), ConvertUtil.toBigDecimal(" 123.45 "));
        Assert.assertNull(ConvertUtil.toBigDecimal("abc"));
        Assert.assertEquals(new BigDecimal("456.78"), ConvertUtil.toBigDecimal("abc", new BigDecimal("456.78")));
        Assert.assertNull(ConvertUtil.toBigDecimal(null));
    }

    @Test
    public void testToFloat() {
        Assert.assertEquals(Float.valueOf(123.45f), ConvertUtil.toFloat(123.45f));
        Assert.assertEquals(Float.valueOf(123.0f), ConvertUtil.toFloat(123));
        Assert.assertEquals(Float.valueOf(123.45f), ConvertUtil.toFloat(" 123.45 "));
        Assert.assertNull(ConvertUtil.toFloat("abc"));
        Assert.assertEquals(Float.valueOf(456.78f), ConvertUtil.toFloat("abc", 456.78f));
        Assert.assertNull(ConvertUtil.toFloat(null));
    }

    @Test
    public void testToShort() {
        Assert.assertEquals(Short.valueOf((short) 123), ConvertUtil.toShort((short) 123));
        Assert.assertEquals(Short.valueOf((short) 123), ConvertUtil.toShort(123));
        Assert.assertEquals(Short.valueOf((short) 123), ConvertUtil.toShort(" 123 "));
        Assert.assertNull(ConvertUtil.toShort("abc"));
        Assert.assertEquals(Short.valueOf((short) 456), ConvertUtil.toShort("abc", (short) 456));
        Assert.assertNull(ConvertUtil.toShort(null));
    }

    @Test
    public void testToByte() {
        Assert.assertEquals(Byte.valueOf((byte) 123), ConvertUtil.toByte((byte) 123));
        Assert.assertEquals(Byte.valueOf((byte) 123), ConvertUtil.toByte(123));
        Assert.assertEquals(Byte.valueOf((byte) 123), ConvertUtil.toByte(" 123 "));
        Assert.assertNull(ConvertUtil.toByte("abc"));
        Assert.assertEquals(Byte.valueOf((byte) 45), ConvertUtil.toByte("abc", (byte) 45));
        Assert.assertNull(ConvertUtil.toByte(null));
    }

    @Test
    public void testToChar() {
        Assert.assertEquals(Character.valueOf('a'), ConvertUtil.toChar('a'));
        Assert.assertEquals(Character.valueOf('a'), ConvertUtil.toChar(" a "));
        Assert.assertEquals(Character.valueOf('1'), ConvertUtil.toChar(1));
        Assert.assertNull(ConvertUtil.toChar(""));
        Assert.assertEquals(Character.valueOf('d'), ConvertUtil.toChar(null, 'd'));
    }

    @Test
    public void testToBool() {
        Assert.assertTrue(ConvertUtil.toBool(true));
        Assert.assertTrue(ConvertUtil.toBool("true"));
        Assert.assertTrue(ConvertUtil.toBool("1"));
        Assert.assertTrue(ConvertUtil.toBool("yes"));
        Assert.assertTrue(ConvertUtil.toBool("ok"));
        Assert.assertTrue(ConvertUtil.toBool("on"));
        Assert.assertTrue(ConvertUtil.toBool("y"));
        Assert.assertTrue(ConvertUtil.toBool("  TRUE  "));

        Assert.assertFalse(ConvertUtil.toBool(false));
        Assert.assertFalse(ConvertUtil.toBool("false"));
        Assert.assertFalse(ConvertUtil.toBool("0"));
        Assert.assertFalse(ConvertUtil.toBool("no"));
        Assert.assertFalse(ConvertUtil.toBool("off"));
        Assert.assertFalse(ConvertUtil.toBool("n"));

        Assert.assertNull(ConvertUtil.toBool("abc"));
        Assert.assertTrue(ConvertUtil.toBool("abc", true));
        Assert.assertNull(ConvertUtil.toBool(null));
    }

    @Test
    public void testToList() {
        // 测试 null
        Assert.assertTrue(ConvertUtil.toList(null).isEmpty());

        // 测试 List
        List<String> list = Arrays.asList("a", "b");
        Assert.assertSame(list, ConvertUtil.toList(list));

        // 测试 Collection
        Assert.assertEquals(list, ConvertUtil.toList(new ArrayList<>(list)));

        // 测试数组
        String[] array = {"a", "b"};
        Assert.assertEquals(list, ConvertUtil.toList(array));

        // 测试逗号分隔字符串
        Assert.assertEquals(Arrays.asList("a", "b", "c"), ConvertUtil.toList("a, b, c"));

        // 测试单个对象
        Assert.assertEquals(Arrays.asList("a"), ConvertUtil.toList("a"));
    }

    public static class TestBean {
        private String name;
        private int age;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public int getAge() {
            return age;
        }

        public void setAge(int age) {
            this.age = age;
        }
    }
}
