package com.team4u.framework.base.util;

import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
/**
 * 集合工具类单元测试
 *
 * @author jay.wu
 */
public class CollectionUtilTest {

    @Test
    public void isEmptyCollection() {
        Assert.assertTrue("null 集合应为空", CollectionUtil.isEmpty((java.util.Collection<?>) null));
        Assert.assertTrue("空集合应为空", CollectionUtil.isEmpty(Collections.emptyList()));
        Assert.assertFalse("非空集合不应为空", CollectionUtil.isEmpty(Collections.singletonList("a")));
    }

    @Test
    public void isNotEmptyCollection() {
        Assert.assertFalse("null 集合不应为非空", CollectionUtil.isNotEmpty((java.util.Collection<?>) null));
        Assert.assertFalse("空集合不应为非空", CollectionUtil.isNotEmpty(Collections.emptyList()));
        Assert.assertTrue("非空集合应为非空", CollectionUtil.isNotEmpty(Collections.singletonList("a")));
    }

    @Test
    public void sizeCollection() {
        Assert.assertEquals(0, CollectionUtil.size((java.util.Collection<?>) null));
        Assert.assertEquals(1, CollectionUtil.size(Collections.singletonList("a")));
    }

    @Test
    public void sizeObject() {
        Assert.assertEquals(0, CollectionUtil.size((Object) null));

        // Collection
        Assert.assertEquals(1, CollectionUtil.size(Collections.singletonList("a")));

        // Map
        Assert.assertEquals(1, CollectionUtil.size(Collections.singletonMap("k", "v")));

        // Object Array
        Assert.assertEquals(2, CollectionUtil.size(new Object[]{"a", "b"}));

        // Primitive Arrays
        Assert.assertEquals(2, CollectionUtil.size(new int[]{1, 2}));
        Assert.assertEquals(2, CollectionUtil.size(new long[]{1L, 2L}));
        Assert.assertEquals(2, CollectionUtil.size(new double[]{1.0, 2.0}));
        Assert.assertEquals(2, CollectionUtil.size(new float[]{1.0f, 2.0f}));
        Assert.assertEquals(2, CollectionUtil.size(new byte[]{1, 2}));
        Assert.assertEquals(2, CollectionUtil.size(new char[]{'a', 'b'}));
        Assert.assertEquals(2, CollectionUtil.size(new short[]{1, 2}));
        Assert.assertEquals(2, CollectionUtil.size(new boolean[]{true, false}));

        // Iterable
        List<String> list = new ArrayList<>();
        list.add("a");
        list.add("b");
        Assert.assertEquals(2, CollectionUtil.size((Iterable<String>) list));

        // Other type
        Assert.assertEquals(0, CollectionUtil.size("string"));
    }
}
