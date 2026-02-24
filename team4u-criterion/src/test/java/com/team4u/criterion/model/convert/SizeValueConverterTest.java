package com.team4u.criterion.model.convert;

import org.junit.Assert;
import org.junit.Test;

import java.util.*;
import java.util.function.Function;

/**
 * 大小/长度转换器单元测试
 *
 * @author jay.wu
 */
public class SizeValueConverterTest {

    private final SizeValueConverter converter = new SizeValueConverter();

    @Test
    public void testKey() {
        Assert.assertEquals("size", converter.key());
    }

    @Test
    public void testNullValue() {
        Assert.assertEquals(0, converter.apply(null));
    }

    @Test
    public void testCollection() {
        List<String> list = Arrays.asList("a", "b", "c");
        Assert.assertEquals(3, converter.apply(list));

        Set<Integer> set = new HashSet<>(Arrays.asList(1, 2, 3, 4, 5));
        Assert.assertEquals(5, converter.apply(set));

        List<Object> empty = new ArrayList<>();
        Assert.assertEquals(0, converter.apply(empty));
    }

    @Test
    public void testMap() {
        Map<String, Integer> map = new HashMap<>();
        map.put("a", 1);
        map.put("b", 2);
        map.put("c", 3);
        Assert.assertEquals(3, converter.apply(map));

        Map<Object, Object> empty = new HashMap<>();
        Assert.assertEquals(0, converter.apply(empty));
    }

    @Test
    public void testString() {
        Assert.assertEquals(5, converter.apply("hello"));
        Assert.assertEquals(0, converter.apply(""));
        Assert.assertEquals(2, converter.apply("中文"));
    }

    @Test
    public void testStringBuilder() {
        StringBuilder sb = new StringBuilder("test");
        Assert.assertEquals(4, converter.apply(sb));
    }

    @Test
    public void testArray() {
        int[] intArray = {1, 2, 3, 4, 5};
        Assert.assertEquals(5, converter.apply(intArray));

        String[] strArray = {"a", "b"};
        Assert.assertEquals(2, converter.apply(strArray));

        Object[] emptyArray = {};
        Assert.assertEquals(0, converter.apply(emptyArray));
    }

    @Test
    public void testUnsupportedType() {
        Assert.assertEquals(0, converter.apply(123));
        Assert.assertEquals(0, converter.apply(true));
    }

    @Test
    public void testIntegrationWithRegistry() {
        ValueConverterRegistry registry = new ValueConverterRegistry();
        registry.register(new SizeValueConverter());

        Function<Object, Comparable<?>> sizeConverter = registry.policyOf("size");
        Assert.assertNotNull(sizeConverter);

        List<String> list = Arrays.asList("a", "b", "c", "d");
        Assert.assertEquals(4, sizeConverter.apply(list));

        Assert.assertEquals(5, sizeConverter.apply("hello"));
    }
}
