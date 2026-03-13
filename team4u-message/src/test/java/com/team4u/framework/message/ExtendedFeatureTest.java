package com.team4u.framework.message;

import com.team4u.framework.base.util.ThrowingConsumer;
import com.team4u.framework.message.core.*;
import lombok.Data;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 补全特性的集成测试
 */
public class ExtendedFeatureTest {

    @Test
    public void testHandlerBuilderAndExtractor() throws Exception {
        // 1. 使用 Lambda 快速构建处理器
        final AtomicBoolean handled = new AtomicBoolean(false);
        List<MessageHandler<?>> handlers = MessageHandlerBuilder.create()
                .onMessage(OrderEvent.class, msg -> {
                    Assert.assertEquals("ORD-100", msg.getPayload().getOrderId());
                    handled.set(true);
                })
                .build();

        MessageDispatcher dispatcher = new MessageDispatcher();
        for (MessageHandler<?> handler : handlers) {
            dispatcher.addHandler(handler);
        }

        // 2. 模拟一个纯 JSON 字符串消息
        String jsonPayload = "{\"orderId\":\"ORD-100\", \"type\":\"OrderEvent\"}";

        // 3. 使用提取器推断类型并封装为信封
        MessageExtractor extractor = new MessageExtractor.JsonPropertyExtractor("type");
        String inferredType = extractor.extractType(jsonPayload);

        // 模拟路由与分发逻辑
        if ("OrderEvent".equals(inferredType)) {
            OrderEvent event = new OrderEvent();
            event.setOrderId("ORD-100");
            dispatcher.dispatch(new GenericMessage<OrderEvent>(event));
        }

        Assert.assertTrue("处理器应成功被触发", handled.get());
    }

    @Data
    public static class OrderEvent {
        private String orderId;
    }
}
