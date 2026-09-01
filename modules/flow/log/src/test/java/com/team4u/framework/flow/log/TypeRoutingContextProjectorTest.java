package com.team4u.framework.flow.log;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.junit.Assert;
import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * TypeRoutingContextProjector 单元测试
 *
 * @author jay.wu
 */
public class TypeRoutingContextProjectorTest {

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    static class UserVerifyReq {
        private String userId;
        private String realName;
        private int level;
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    static class PaymentOrderDTO {
        private String orderId;
        private Double amount;
        private String cardNo;
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    @lombok.EqualsAndHashCode(callSuper = false)
    static class SubPaymentOrderDTO extends PaymentOrderDTO {
        private String extraTag;
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    @TraceContext
    static class FallbackAnnotatedDTO {
        private String note;
    }

    @Test
    public void testTypeRoutingWithOfCustomFunctionAndFields() {
        ContextProjector projector = ContextProjector.byType()
                // 融入 of 函数式自定义值能力
                .bind(UserVerifyReq.class, (UserVerifyReq req) -> {
                    Map<String, Object> map = new HashMap<String, Object>();
                    map.put("uid", req.getUserId());
                    map.put("badge", "VIP-" + req.getLevel());
                    return map;
                })
                // 属性白名单
                .bindFields(PaymentOrderDTO.class, "orderId", "amount")
                // 兜底回退至注解模式
                .fallback(ContextProjector.annotated())
                .build();

        // 1. 验证 UserVerifyReq 命中 Lambda 自定义转换 (融入 of)
        UserVerifyReq user = new UserVerifyReq("U1001", "张三", 5);
        Object userResult = projector.project(user);
        Assert.assertTrue(userResult instanceof Map);
        Map<?, ?> userMap = (Map<?, ?>) userResult;
        Assert.assertEquals("U1001", userMap.get("uid"));
        Assert.assertEquals("VIP-5", userMap.get("badge"));
        Assert.assertNull(userMap.get("realName"));

        // 2. 验证 PaymentOrderDTO 命中白名单模式
        PaymentOrderDTO pay = new PaymentOrderDTO("ORD-999", 88.8, "6222020011223344");
        Object payResult = projector.project(pay);
        Assert.assertTrue(payResult instanceof Map);
        Map<?, ?> payMap = (Map<?, ?>) payResult;
        Assert.assertEquals("ORD-999", payMap.get("orderId"));
        Assert.assertEquals(88.8, payMap.get("amount"));
        Assert.assertNull(payMap.get("cardNo"));

        // 3. 验证子类继承匹配 (SubPaymentOrderDTO isAssignableFrom PaymentOrderDTO)
        SubPaymentOrderDTO subPay = new SubPaymentOrderDTO();
        subPay.setOrderId("ORD-SUB");
        subPay.setAmount(100.0);
        subPay.setExtraTag("tag");
        Object subPayResult = projector.project(subPay);
        Assert.assertTrue(subPayResult instanceof Map);
        Map<?, ?> subPayMap = (Map<?, ?>) subPayResult;
        Assert.assertEquals("ORD-SUB", subPayMap.get("orderId"));
        Assert.assertEquals(100.0, subPayMap.get("amount"));

        // 4. 验证未注册类回退至 fallback (AnnotatedContextProjector)
        FallbackAnnotatedDTO fallbackDTO = new FallbackAnnotatedDTO("fallback-note");
        Object fallbackResult = projector.project(fallbackDTO);
        Assert.assertTrue(fallbackResult instanceof Map);
        Map<?, ?> fallbackMap = (Map<?, ?>) fallbackResult;
        Assert.assertEquals("fallback-note", fallbackMap.get("note"));

        // 5. 边界值测试
        Assert.assertNull(projector.project(null));
    }

    @Test
    public void testByTypeWithMapConstructorAndNullFallback() {
        Map<Class<?>, ContextProjector> map = new HashMap<Class<?>, ContextProjector>();
        map.put(PaymentOrderDTO.class, ContextProjector.fields(Collections.singletonList("orderId")));

        ContextProjector projector = ContextProjector.byType()
                .bindAll(map)
                .fallback(null)
                .build();

        PaymentOrderDTO pay = new PaymentOrderDTO("ORD-1", 10.0, "CARD");
        Map<?, ?> payMap = (Map<?, ?>) projector.project(pay);
        Assert.assertEquals("ORD-1", payMap.get("orderId"));
        Assert.assertNull(payMap.get("amount"));

        // 未注册且 fallback 为 null 时直接透传原对象
        UserVerifyReq unmapped = new UserVerifyReq("U1", "李四", 1);
        Assert.assertSame(unmapped, projector.project(unmapped));
    }
}
