package com.team4u.framework.criterion;

import org.junit.Assert;
import org.junit.Test;

/**
 * 匹配上下文单元测试
 */
public class MatchContextTest {

    @Test
    public void testAttributeSharing() {
        // 创建初始上下文并设置属性
        MatchContext context1 = MatchContext.of("value1");
        context1.setAttribute("key1", "attr1");

        // 使用 withActual 创建新上下文
        MatchContext context2 = context1.withActual("value2");

        // 验证属性是否共享（通过引用）
        Assert.assertEquals("attr1", context2.getAttribute("key1"));

        // 修改 context1 的属性，context2 应该同步感知
        context1.setAttribute("key1", "updated");
        Assert.assertEquals("updated", context2.getAttribute("key1"));

        // 修改 context2 的属性，context1 也应该同步感知
        context2.setAttribute("key2", "attr2");
        Assert.assertEquals("attr2", context1.getAttribute("key2"));

        // 验证实际值是否独立
        Assert.assertEquals("value1", context1.getActual());
        Assert.assertEquals("value2", context2.getActual());
    }

    @Test
    public void testBasicFunctions() {
        MatchContext context = new MatchContext("test");
        context.setAttribute("a", 1).setAttribute("b", null);

        Assert.assertEquals("test", context.getActual());
        Assert.assertEquals(1, (int) context.getAttribute("a"));
        Assert.assertNull(context.getAttribute("b"));
    }

    @Test
    public void testLazyResolver() {
        MatchContext context = new MatchContext("test");
        context.setAttributeResolver((ctx, key) -> "lazyValue_" + key);

        // 第一次获取，应该调用 lazyResolver 并写入缓存
        Assert.assertEquals("lazyValue_a", context.getAttribute("a"));
        // 验证确实写入了属性 Map
        Assert.assertEquals("lazyValue_a", context.getAttributes().get("a"));

        // 修改 lazyResolver，验证是否直接从缓存读取，不再调用解析器
        context.setAttributeResolver((ctx, key) -> "newValue_" + key);
        Assert.assertEquals("lazyValue_a", context.getAttribute("a"));

        // 判断属性存在但 value 为 null 的情况，不应该触发 lazyResolver
        context.getAttributes().put("b", null);
        Assert.assertNull(context.getAttribute("b"));
    }

    @Test
    public void testWithActualInheritsLazyResolver() {
        MatchContext context1 = new MatchContext("test1");
        context1.setAttributeResolver((ctx, key) -> "lazyValue");

        MatchContext context2 = context1.withActual("test2");
        Assert.assertEquals("lazyValue", context2.getAttribute("a"));
    }
}
