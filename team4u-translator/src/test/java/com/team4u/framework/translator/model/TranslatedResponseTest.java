package com.team4u.framework.translator.model;

import org.junit.Assert;
import org.junit.Test;

/**
 * TranslatedResponse 不可变特性及基础功能测试
 */
public class TranslatedResponseTest {

    /**
     * 测试不可变实体类的基础功能，确保能够安全地作为返回值传递
     */
    @Test
    public void testValueSemantics() {
        TranslatedResponse left = new TranslatedResponse("C1", "msg", "trace-1");
        TranslatedResponse right = new TranslatedResponse("C1", "msg", "trace-1");

        Assert.assertEquals(left, right);
        Assert.assertEquals(left.hashCode(), right.hashCode());
        Assert.assertTrue(left.toString().contains("code=C1"));
        Assert.assertTrue(left.toString().contains("message=msg"));
        Assert.assertTrue(left.toString().contains("traceId=trace-1"));
    }
}
