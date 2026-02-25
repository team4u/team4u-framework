package com.team4u.framework.criterion.util;

import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

public class CriterionCollectionUtilTest {

    @Test
    public void toCollection_null_returnsNull() {
        Assert.assertNull(CriterionCollectionUtil.toCollection(null));
    }

    @Test
    public void toCollection_collection_returnsSame() {
        List<String> list = Arrays.asList("a", "b");
        Assert.assertSame(list, CriterionCollectionUtil.toCollection(list));
    }

    @Test
    public void toCollection_objectArray_returnsList() {
        String[] array = {"a", "b"};
        Collection<?> result = CriterionCollectionUtil.toCollection(array);
        Assert.assertTrue(result instanceof List);
        Assert.assertEquals(2, result.size());
        Assert.assertTrue(result.contains("a"));
    }

    @Test
    public void toCollection_primitiveArray_returnsList() {
        int[] array = {1, 2};
        Collection<?> result = CriterionCollectionUtil.toCollection(array);
        Assert.assertEquals(2, result.size());
        Assert.assertTrue(result.contains(1));
    }

    @Test
    public void toCollection_iterator_returnsList() {
        Iterator<String> it = Arrays.asList("a", "b").iterator();
        Collection<?> result = CriterionCollectionUtil.toCollection(it);
        Assert.assertEquals(2, result.size());
        Assert.assertTrue(result.contains("a"));
    }

    @Test
    public void toCollection_iterable_returnsList() {
        Iterable<String> it = () -> Arrays.asList("a", "b").iterator();
        Collection<?> result = CriterionCollectionUtil.toCollection(it);
        Assert.assertEquals(2, result.size());
        Assert.assertTrue(result.contains("a"));
    }

    @Test
    public void toCollection_singleValue_returnsSingletonList() {
        String value = "a";
        Collection<?> result = CriterionCollectionUtil.toCollection(value);
        Assert.assertEquals(1, result.size());
        Assert.assertTrue(result.contains("a"));
    }
}
