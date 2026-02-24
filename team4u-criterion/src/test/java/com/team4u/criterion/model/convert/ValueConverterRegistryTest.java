package com.team4u.criterion.model.convert;

import org.junit.Assert;
import org.junit.Test;
import com.team4u.criterion.Criteria;

import java.util.Date;
import java.util.function.Function;

/**
 * 类型转换器注册表单元测试
 *
 * @author jay.wu
 */
public class ValueConverterRegistryTest {

    @Test
    public void testBuiltInConverters() {
        ValueConverterRegistry registry = Criteria.builder().getConverterRegistry();

        // 测试日期转换器
        Function<Object, Comparable<?>> dateConverter = registry.policyOf("date");
        Assert.assertNotNull(dateConverter);
        Assert.assertTrue(dateConverter.apply("now") instanceof Date);
        Assert.assertNotNull(dateConverter.apply("2023-01-01"));

        // 测试版本转换器
        Function<Object, Comparable<?>> versionConverter = registry.policyOf("version");
        Assert.assertNotNull(versionConverter);
        Comparable<Object> v1 = (Comparable<Object>) versionConverter.apply("1.0.0");
        Comparable<Object> v2 = (Comparable<Object>) versionConverter.apply("1.0.1");
        Assert.assertTrue(v1.compareTo(v2) < 0);

        // 测试数值转换器
        Function<Object, Comparable<?>> numberConverter = registry.policyOf("number");
        Assert.assertNotNull(numberConverter);
        Assert.assertEquals(100.5D, numberConverter.apply("100.5"));

        // 测试字符串转换器
        Function<Object, Comparable<?>> stringConverter = registry.policyOf("string");
        Assert.assertNotNull(stringConverter);
        Assert.assertEquals("123", stringConverter.apply(123));
    }

    @Test
    public void testCustomRegister() {
        ValueConverterRegistry registry = new ValueConverterRegistry();

        // 测试手动注册转换器对象
        registry.register(new ValueConverter() {
            @Override
            public String id() {
                return "custom";
            }

            @Override
            public Comparable<?> apply(Object obj) {
                return obj + "_suffix";
            }

            @Override
            public String key() {
                return id();
            }
        });
        Function<Object, Comparable<?>> customConverter = registry.policyOf("custom");
        Assert.assertNotNull(customConverter);
        Assert.assertEquals("test_suffix", customConverter.apply("test"));
    }
}
