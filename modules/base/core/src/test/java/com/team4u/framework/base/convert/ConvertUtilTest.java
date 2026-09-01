package com.team4u.framework.base.convert;

import com.team4u.framework.base.util.TypeReference;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * ConvertUtil 单元测试
 *
 * @author jay.wu
 */
public class ConvertUtilTest {

    @Before
    public void setUp() {
        ConvertUtil.resetDefaultConverters();
    }

    @After
    public void tearDown() {
        ConvertUtil.resetDefaultConverters();
    }

    @Test
    public void testConvert() {
        Assert.assertEquals("123", ConvertUtil.convert(String.class, 123));
        Assert.assertEquals("123", ConvertUtil.convert(String.class, 123L));
        Assert.assertNull(ConvertUtil.convert(String.class, null));
        Assert.assertEquals("default", ConvertUtil.convert(String.class, null, "default"));

        Assert.assertEquals(Long.valueOf(123L), ConvertUtil.convert(Long.class, "123"));
        Assert.assertEquals(Long.valueOf(123L), ConvertUtil.convert(long.class, 123));
        Assert.assertNull(ConvertUtil.convert(Long.class, "abc"));
        Assert.assertEquals(Long.valueOf(456L), ConvertUtil.convert(Long.class, "abc", 456L));

        Assert.assertEquals(Integer.valueOf(123), ConvertUtil.convert(Integer.class, "123"));
        Assert.assertEquals(Integer.valueOf(123), ConvertUtil.convert(int.class, 123L));
        Assert.assertNull(ConvertUtil.convert(Integer.class, "abc"));
        Assert.assertEquals(Integer.valueOf(456), ConvertUtil.convert(Integer.class, "abc", 456));

        Assert.assertEquals(Double.valueOf(123.45), ConvertUtil.convert(Double.class, "123.45"));
        Assert.assertEquals(Double.valueOf(123.0), ConvertUtil.convert(double.class, 123));
        Assert.assertNull(ConvertUtil.convert(Double.class, "abc"));
        Assert.assertEquals(Double.valueOf(456.78), ConvertUtil.convert(Double.class, "abc", 456.78));

        Assert.assertEquals(new BigDecimal("123.45"), ConvertUtil.convert(BigDecimal.class, "123.45"));
        Assert.assertNull(ConvertUtil.convert(BigDecimal.class, "abc"));
        Assert.assertEquals(new BigInteger("12345"), ConvertUtil.convert(BigInteger.class, "12345"));

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

        Assert.assertEquals(Float.valueOf(123.45f), ConvertUtil.convert(Float.class, "123.45"));
        Assert.assertEquals(Float.valueOf(123.0f), ConvertUtil.convert(float.class, 123));

        Assert.assertEquals(Short.valueOf((short) 123), ConvertUtil.convert(Short.class, "123"));
        Assert.assertEquals(Short.valueOf((short) 123), ConvertUtil.convert(short.class, 123));

        Assert.assertEquals(Byte.valueOf((byte) 123), ConvertUtil.convert(Byte.class, "123"));
        Assert.assertEquals(Byte.valueOf((byte) 123), ConvertUtil.convert(byte.class, 123));

        Assert.assertEquals(Character.valueOf('a'), ConvertUtil.convert(Character.class, "a"));
        Assert.assertEquals(Character.valueOf('1'), ConvertUtil.convert(char.class, 1));

        Assert.assertArrayEquals(new String[]{"a", "b", "c"}, ConvertUtil.convert(String[].class, "a, b, c"));
        Assert.assertArrayEquals(new Integer[]{1, 2, 3}, ConvertUtil.convert(Integer[].class, "1, 2, 3"));
        Assert.assertArrayEquals(new int[]{1, 2, 3}, ConvertUtil.convert(int[].class, "1, 2, 3"));

        Assert.assertEquals(new BigDecimal("123.45"), ConvertUtil.convert(Number.class, "123.45"));

        Map<String, Object> map = new HashMap<>();
        map.put("name", "test");
        map.put("age", 20);
        TestBean bean = ConvertUtil.convert(TestBean.class, map);
        Assert.assertNotNull(bean);
        Assert.assertEquals("test", bean.getName());
        Assert.assertEquals(20, bean.getAge());
    }

    @Test
    public void testTemporalAndEnumConvert() {
        Assert.assertEquals(TestEnum.HIGH, ConvertUtil.convert(TestEnum.class, "HIGH"));
        Assert.assertEquals(TestEnum.LOW, ConvertUtil.convert(TestEnum.class, "low"));

        Date date = ConvertUtil.convert(Date.class, "2023-01-01 12:00:00");
        Assert.assertNotNull(date);
        Assert.assertEquals(LocalDate.of(2023, 1, 1), ConvertUtil.convert(LocalDate.class, "2023-01-01"));
        Assert.assertEquals(LocalDateTime.of(2023, 1, 1, 12, 0, 0),
                ConvertUtil.convert(LocalDateTime.class, "2023-01-01 12:00:00"));

        Instant instant = ConvertUtil.convert(Instant.class, "1700000000000");
        Assert.assertEquals(1700000000000L, instant.toEpochMilli());

        Assert.assertEquals(Duration.ofSeconds(5), ConvertUtil.convert(Duration.class, "5s"));
        Assert.assertEquals(Duration.ofMillis(500), ConvertUtil.convert(Duration.class, 500L));
        Assert.assertEquals(Duration.ofSeconds(10), ConvertUtil.convert(Duration.class, Duration.ofSeconds(10)));
        Assert.assertNull(ConvertUtil.convert(Duration.class, null));
        Assert.assertEquals(Duration.ofSeconds(1), ConvertUtil.convert(Duration.class, "invalid", Duration.ofSeconds(1)));
    }

    @Test
    public void testToDuration() {
        Assert.assertNull(ConvertUtil.toDuration(null));
        Assert.assertEquals(Duration.ofSeconds(5), ConvertUtil.toDuration(null, Duration.ofSeconds(5)));
        Assert.assertEquals(Duration.ofSeconds(3), ConvertUtil.toDuration(Duration.ofSeconds(3)));

        // Number
        Assert.assertEquals(Duration.ofMillis(500), ConvertUtil.toDuration(500));
        Assert.assertEquals(Duration.ofMillis(1000), ConvertUtil.toDuration(1000L));
        Assert.assertEquals(Duration.ofMillis(2500), ConvertUtil.toDuration(2500.0));

        // String units
        Assert.assertEquals(Duration.ofNanos(100), ConvertUtil.toDuration("100ns"));
        Assert.assertEquals(Duration.ofNanos(100_000), ConvertUtil.toDuration("100us"));
        Assert.assertEquals(Duration.ofNanos(100_000), ConvertUtil.toDuration("100µs"));
        Assert.assertEquals(Duration.ofMillis(100), ConvertUtil.toDuration("100ms"));
        Assert.assertEquals(Duration.ofMillis(100), ConvertUtil.toDuration(" 100MS "));
        Assert.assertEquals(Duration.ofSeconds(5), ConvertUtil.toDuration("5s"));
        Assert.assertEquals(Duration.ofSeconds(5), ConvertUtil.toDuration(" 5S "));
        Assert.assertEquals(Duration.ofMinutes(10), ConvertUtil.toDuration("10m"));
        Assert.assertEquals(Duration.ofHours(1), ConvertUtil.toDuration("1h"));
        Assert.assertEquals(Duration.ofDays(2), ConvertUtil.toDuration("2d"));

        // Pure numeric string
        Assert.assertEquals(Duration.ofMillis(500), ConvertUtil.toDuration("500"));
        Assert.assertEquals(Duration.ofMillis(500), ConvertUtil.toDuration(" 500 "));

        // ISO-8601
        Assert.assertEquals(Duration.ofSeconds(10), ConvertUtil.toDuration("PT10S"));
        Assert.assertEquals(Duration.ofSeconds(10), ConvertUtil.toDuration("\"PT10S\""));
        Assert.assertEquals(Duration.ofSeconds(10), ConvertUtil.toDuration("pt10s"));
        Assert.assertEquals(Duration.ofDays(1), ConvertUtil.toDuration("P1D"));

        // Fallback / invalid
        Assert.assertNull(ConvertUtil.toDuration("invalid"));
        Assert.assertEquals(Duration.ofSeconds(1), ConvertUtil.toDuration("invalid", Duration.ofSeconds(1)));
        Assert.assertNull(ConvertUtil.toDuration(""));
    }

    @Test
    public void testParameterizedCollectionConvert() {
        Type integerListType = new TypeReference<List<Integer>>() {
        }.getType();
        Type longSetType = new TypeReference<Set<Long>>() {
        }.getType();
        Type boolCollectionType = new TypeReference<Collection<Boolean>>() {
        }.getType();

        Assert.assertEquals(Arrays.asList(1, 2, 3), ConvertUtil.convert(integerListType, "1, 2, 3"));
        Assert.assertEquals(new LinkedHashSet<>(Arrays.asList(1L, 2L, 3L)),
                ConvertUtil.convert(longSetType, Arrays.asList("1", 2, 3L)));
        Assert.assertEquals(Arrays.asList(true, false, true),
                new ArrayList<Boolean>(ConvertUtil.convert(boolCollectionType, "true, false, yes")));
    }

    @Test
    @SuppressWarnings("rawtypes")
    public void testTypeReferenceRequiresGenericArgument() {
        try {
            new TypeReference() {
            };
            Assert.fail("Should reject raw TypeReference");
        } catch (IllegalArgumentException e) {
            Assert.assertTrue(e.getMessage().contains("具体的泛型参数"));
        }
    }

    @Test
    public void testCustomConverterLifecycle() {
        ConvertUtil.registerConverter(new TestBeanConverter());

        TestBean bean = ConvertUtil.convert(TestBean.class, "alice:18");
        Assert.assertNotNull(bean);
        Assert.assertEquals("alice", bean.getName());
        Assert.assertEquals(18, bean.getAge());

        ConvertUtil.removeConverter(TestBeanConverter.class);
        Assert.assertNull(ConvertUtil.convert(TestBean.class, "alice:18"));
    }

    @Test
    public void testCustomConverterExceptionFailsFast() {
        ConvertUtil.registerConverter(new ExplodingIntegerConverter());
        try {
            ConvertUtil.convert(Integer.class, "10", 9);
            Assert.fail("Should throw TypeConversionException");
        } catch (TypeConversionException e) {
            Assert.assertTrue(e.getMessage().contains(ExplodingIntegerConverter.class.getName()));
        }
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
        Assert.assertTrue(ConvertUtil.toList(null).isEmpty());

        List<String> list = Arrays.asList("a", "b");
        Assert.assertSame(list, ConvertUtil.toList(list));
        Assert.assertEquals(list, ConvertUtil.toList(new ArrayList<>(list)));

        String[] array = {"a", "b"};
        Assert.assertEquals(list, ConvertUtil.toList(array));
        Assert.assertEquals(Arrays.asList("a", "b", "c"), ConvertUtil.toList("a, b, c"));
        Assert.assertEquals(Collections.singletonList("a"), ConvertUtil.toList("a"));
    }

    private enum TestEnum {
        LOW,
        HIGH
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

    private static class TestBeanConverter implements TypeConverter {

        @Override
        public boolean supports(Type targetType, Object source) {
            return targetType == TestBean.class && source instanceof String;
        }

        @Override
        public Object convert(Type targetType, Object source) {
            String[] parts = source.toString().split(":");
            TestBean bean = new TestBean();
            bean.setName(parts[0]);
            bean.setAge(Integer.parseInt(parts[1]));
            return bean;
        }

        @Override
        public int order() {
            return -10;
        }
    }

    private static class ExplodingIntegerConverter implements TypeConverter {

        @Override
        public boolean supports(Type targetType, Object source) {
            return targetType == Integer.class;
        }

        @Override
        public Object convert(Type targetType, Object source) {
            throw new IllegalStateException("boom");
        }

        @Override
        public int order() {
            return -100;
        }
    }
}
