package com.team4u.framework.message;

import com.team4u.framework.message.channel.jvm.JvmMessageChannel;
import com.team4u.framework.message.core.*;
import com.team4u.framework.message.core.interceptor.MessageInterceptor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 消息框架完整集成测试
 */
@Slf4j
public class MessageFrameworkTest {

    @Test
    public void testFullLifecycle() throws Exception {
        // 1. 初始化分发器与本地通道
        MessageDispatcher dispatcher = new MessageDispatcher();
        JvmMessageChannel channel = new JvmMessageChannel("test-channel", dispatcher);

        // 用于记录执行顺序的容器
        List<String> executionLog = new ArrayList<>();
        AtomicInteger handledCount = new AtomicInteger(0);

        // 2. 注册一个通用拦截器
        dispatcher.addInterceptor(new MessageInterceptor() {
            @Override
            public boolean preHandle(Message<?> message) {
                executionLog.add("Interceptor:Pre");
                return true;
            }

            @Override
            public void postHandle(Message<?> message) {
                executionLog.add("Interceptor:Post");
            }

            @Override
            public void afterCompletion(Message<?> message, Exception ex) {
                executionLog.add("Interceptor:After");
            }
        });

        // 3. 注册特定类型的处理器
        dispatcher.addHandler(new MessageHandler<OrderCreatedEvent>() {
            @Override
            public Class<OrderCreatedEvent> supportedPayloadType() {
                return OrderCreatedEvent.class;
            }

            @Override
            public void handle(Message<OrderCreatedEvent> message) {
                executionLog.add("Handler:OrderCreated");
                handledCount.incrementAndGet();
                log.info("Handled order: {}", message.getPayload().getOrderId());
            }
        });

        // 4. 发送不匹配的消息 (应该被忽略)
        channel.send(new GenericMessage<>("Hello World"));
        Assert.assertEquals("不匹配的消息不应触发处理器", 0, handledCount.get());

        // 5. 发送匹配的消息
        OrderCreatedEvent event = new OrderCreatedEvent();
        event.setOrderId("ORD-001");
        channel.send(new GenericMessage<>(event));

        // 6. 验证执行顺序和结果
        Assert.assertEquals("处理器应被执行一次", 1, handledCount.get());
        
        // 验证生命周期顺序: Pre -> Handler -> Post -> After
        Assert.assertEquals("拦截器正序前置应最先执行", "Interceptor:Pre", executionLog.get(0));
        Assert.assertEquals("业务处理器应在 Pre 之后执行", "Handler:OrderCreated", executionLog.get(1));
        Assert.assertEquals("拦截器正序后置应在 Handler 之后执行", "Interceptor:Post", executionLog.get(2));
        Assert.assertEquals("拦截器完成后回调应最后执行", "Interceptor:After", executionLog.get(3));

        log.info("Full lifecycle test passed successfully!");
    }

    /**
     * 测试数据载荷
     */
    @Data
    public static class OrderCreatedEvent {
        private String orderId;
    }
}
