package com.team4u.framework.message;

import com.team4u.framework.message.channel.jvm.JvmMessageChannel;
import com.team4u.framework.message.core.*;
import lombok.Data;
import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * DX 增强层集成测试
 */
public class DeveloperExperienceTest {

    @Test
    public void testAbstractHandlerAndTemplate() throws Exception {
        // 1. 初始化分发环境
        MessageDispatcher dispatcher = new MessageDispatcher();
        JvmMessageChannel channel = new JvmMessageChannel("dx-channel", dispatcher);
        
        // 2. 使用统一门面 Template
        MessageTemplate template = new MessageTemplate(channel);

        // 3. 定义并注册一个基于基类的处理器
        final AtomicBoolean handled = new AtomicBoolean(false);
        AbstractMessageHandler<UserEvent> handler = new AbstractMessageHandler<UserEvent>() {
            @Override
            protected void onMessage(UserEvent payload, MessageHeaders headers) {
                Assert.assertEquals("Alice", payload.getUsername());
                Assert.assertNotNull(headers.getId());
                handled.set(true);
            }
        };
        dispatcher.addHandler(handler);

        // 4. 业务侧只需直接发送裸对象
        UserEvent event = new UserEvent();
        event.setUsername("Alice");
        template.send(event);

        Assert.assertTrue("AbstractMessageHandler 应成功接收并拆解消息内容", handled.get());
    }

    @Data
    public static class UserEvent {
        private String username;
    }
}
