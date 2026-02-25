package com.team4u.framework.criterion;

import org.junit.Assert;
import org.junit.Test;

/**
 * 延迟属性解析器单元测试
 *
 * @author jay.wu
 */
public class LazyAttributeResolverTest {

    @Test
    public void testLazyAttributeResolver() {
        MatchContext context = MatchContext.of("test");

        // 创建解析器并以 Supplier 方式注册（无需感知上下文）
        LazyAttributeResolver resolver = new LazyAttributeResolver()
                .register("key1", () -> "value1")
                .register("key2", () -> 100);

        // 验证正确获取注册的值
        Assert.assertEquals("value1", resolver.resolve(context, "key1"));
        Assert.assertEquals(100, resolver.resolve(context, "key2"));

        // 验证未注册的 key 返回 null
        Assert.assertNull(resolver.resolve(context, "key3"));
    }

    @Test
    public void testRegisterWithAttributeResolver() {
        MatchContext context = MatchContext.of("testUser");

        // 以 AttributeResolver 方式注册，可感知上下文
        LazyAttributeResolver resolver = new LazyAttributeResolver()
                .register("userId", (ctx, key) -> ctx.getActual() + "_resolved");

        Assert.assertEquals("testUser_resolved", resolver.resolve(context, "userId"));
    }

    @Test
    public void testIntegrationWithMatchContext() {
        String userId = "1001";
        MatchContext context = MatchContext.of(userId);

        // 创建并设置延迟加载解析器
        LazyAttributeResolver resolver = new LazyAttributeResolver()
                .register("isVip", () -> true)
                .register("score", () -> 50);

        context.setAttributeResolver(resolver);

        // 验证 MatchContext 能够通过解析器动态获取属性
        Assert.assertEquals(true, context.getAttribute("isVip"));
        Assert.assertEquals((Object) 50, context.getAttribute("score"));
        // 验证未定义的属性返回 null
        Assert.assertNull(context.getAttribute("undefined"));
    }
}
