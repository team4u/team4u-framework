package com.team4u.framework.base.util;

import org.junit.Assert;
import org.junit.Test;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * TextTemplate 单元测试
 *
 * @author jay.wu
 */
public class TextTemplateTest {

    @Test
    public void testConstantTemplate() {
        TextTemplate template = new TextTemplate("static-text");
        Assert.assertFalse(template.isDynamic());
        Assert.assertEquals("static-text", template.render((Map<String, Object>) null));
    }

    @Test
    public void testDynamicTemplateWithMap() {
        TextTemplate template = new TextTemplate("biz.${tenantId}.router");
        Assert.assertTrue(template.isDynamic());

        Map<String, Object> context = new HashMap<>();
        context.put("tenantId", "alipay");
        Assert.assertEquals("biz.alipay.router", template.render(context));
    }

    @Test
    public void testDynamicTemplateWithFunction() {
        TextTemplate template = new TextTemplate("user:${id}");
        Assert.assertTrue(template.isDynamic());

        Assert.assertEquals("user:100", template.render(prop -> "100"));
    }

    @Test
    public void testMissingProperty() {
        TextTemplate template = new TextTemplate("hello ${name}");
        Map<String, Object> context = new HashMap<>();
        // 不包含 name 属性，应该保持原样输出占位符
        Assert.assertEquals("hello ${name}", template.render(context));
    }

    @Test
    public void testNullContext() {
        TextTemplate template = new TextTemplate("hello ${name}");
        Assert.assertEquals("hello ${name}", template.render((Map<String, Object>) null));
    }

    @Test
    public void testExtractVariableNames() {
        TextTemplate template = new TextTemplate("biz.${region}.${tenantId}.router");
        Assert.assertEquals(2, template.getVariableNames().size());
        Assert.assertTrue(template.getVariableNames().contains("region"));
        Assert.assertTrue(template.getVariableNames().contains("tenantId"));

        // 保持顺序测试
        Iterator<String> it = template.getVariableNames().iterator();
        Assert.assertEquals("region", it.next());
        Assert.assertEquals("tenantId", it.next());
    }
}
