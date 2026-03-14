package com.team4u.framework.base.convert;

import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Type;
import java.util.List;

/**
 * TypeConverterRegistry 单元测试
 */
public class TypeConverterRegistryTest {

    @Test
    public void testConvertSuccess() {
        TypeConverterRegistry registry = new TypeConverterRegistry();
        registry.resetDefaults();
        Object result = registry.convert(Integer.class, "123");
        Assert.assertEquals(123, result);
    }

    @Test
    public void testCustomConverter() {
        TypeConverterRegistry registry = new TypeConverterRegistry();
        registry.resetDefaults();

        TypeConverter myConverter = new TypeConverter() {
            @Override
            public boolean supports(Type targetType, Object source) {
                return "TEST".equals(source);
            }

            @Override
            public Object convert(Type targetType, Object source) {
                return 999;
            }

            @Override
            public int order() {
                return 0;
            }
        };

        registry.register(myConverter);
        Object result = registry.convert(Integer.class, "TEST");
        Assert.assertEquals(999, result);

        registry.remove(myConverter.getClass());
        List<TypeConverter> converters = registry.getBuiltInConverters();
        Assert.assertTrue(converters.size() > 0);
    }

    @Test
    public void testConvertFailureThrowsException() {
        TypeConverterRegistry registry = new TypeConverterRegistry();
        registry.resetDefaults();

        TypeConverter errorConverter = new TypeConverter() {
            @Override
            public boolean supports(Type targetType, Object source) {
                return "ERROR".equals(source);
            }

            @Override
            public Object convert(Type targetType, Object source) {
                throw new RuntimeException("Test Exception");
            }

            @Override
            public int order() {
                return -1; // 优先级最高
            }
        };

        registry.register(errorConverter);

        try {
            registry.convert(Integer.class, "ERROR");
            Assert.fail("期望抛出 TypeConversionException");
        } catch (TypeConversionException e) {
            Assert.assertTrue(e.getMessage().contains("类型转换失败"));
            Assert.assertTrue(e.getMessage().contains("源数据=ERROR"));
        }
    }
}
