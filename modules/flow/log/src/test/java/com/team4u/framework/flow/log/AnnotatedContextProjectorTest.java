package com.team4u.framework.flow.log;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.junit.Assert;
import org.junit.Test;

import java.util.Map;

public class AnnotatedContextProjectorTest {

    @Data
    @TraceContext
    static class BaseClass {
        private String baseId = "BASE-1";
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    static class ChildWithClassAnnotation extends BaseClass {
        private String orderId = "ORD-1001";
        private String mobile = "13800138000";

        @TraceIgnore
        private String ignoredField = "SECRET";

        private transient String transientField = "TEMP";
        private static String staticField = "STATIC";
    }

    @Data
    static class WhitelistModeClass {
        @TraceContext
        private String orderId = "ORD-2002";

        @TraceContext("userMobile")
        private String mobile = "13900139000";

        private String secretToken = "TOKEN-XYZ";
    }

    @Data
    static class UnannotatedClass {
        private String orderId = "ORD-3003";
        private String description = "DESC";
    }

    @Test
    public void testClassLevelAnnotation() {
        ChildWithClassAnnotation context = new ChildWithClassAnnotation();
        Object projected = AnnotatedContextProjector.INSTANCE.project(context);

        Assert.assertTrue(projected instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) projected;

        Assert.assertEquals("BASE-1", map.get("baseId"));
        Assert.assertEquals("ORD-1001", map.get("orderId"));
        Assert.assertEquals("13800138000", map.get("mobile"));
        Assert.assertFalse(map.containsKey("ignoredField"));
        Assert.assertFalse(map.containsKey("transientField"));
        Assert.assertFalse(map.containsKey("staticField"));
    }

    @Test
    public void testFieldLevelWhitelistMode() {
        WhitelistModeClass context = new WhitelistModeClass();
        Object projected = AnnotatedContextProjector.INSTANCE.project(context);

        Assert.assertTrue(projected instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) projected;

        Assert.assertEquals("ORD-2002", map.get("orderId"));
        Assert.assertEquals("13900139000", map.get("userMobile"));
        Assert.assertFalse(map.containsKey("mobile"));
        Assert.assertFalse(map.containsKey("secretToken"));
    }

    @Test
    public void testUnannotatedClassReturnsOriginal() {
        UnannotatedClass context = new UnannotatedClass();
        Object projected = AnnotatedContextProjector.INSTANCE.project(context);

        Assert.assertSame(context, projected);
    }

    @Test
    public void testNullContext() {
        Assert.assertNull(AnnotatedContextProjector.INSTANCE.project(null));
    }

    @Test
    public void testFieldProjectorWhitelist() {
        ChildWithClassAnnotation context = new ChildWithClassAnnotation();
        ContextProjector projector = ContextProjector.fields("orderId", "ignoredField");
        Object projected = projector.project(context);

        Assert.assertTrue(projected instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) projected;
        Assert.assertEquals(2, map.size());
        Assert.assertEquals("ORD-1001", map.get("orderId"));
        Assert.assertEquals("SECRET", map.get("ignoredField"));
    }

    @Test
    public void testLambdaProjector() {
        ChildWithClassAnnotation context = new ChildWithClassAnnotation();
        ContextProjector projector = ContextProjector.of((ChildWithClassAnnotation ctx) -> ctx.getOrderId());
        Object projected = projector.project(context);

        Assert.assertEquals("ORD-1001", projected);
    }
}
