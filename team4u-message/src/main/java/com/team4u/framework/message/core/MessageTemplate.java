package com.team4u.framework.message.core;

import com.team4u.framework.message.channel.MessageChannel;

import java.util.concurrent.Executor;

/**
 * 消息发送统一门面
 * <p>
 * 为业务开发提供极简的消息投递 API。支持裸对象的自动封包处理、
 * 多维度的异步发送控制以及回调监听机制，从而规避直接操作低层通道接口的复杂性。
 *
 * @author jay.wu
 */
public class MessageTemplate {

    private final MessageChannel defaultChannel;

    /**
     * @param defaultChannel 门面关联的默认分发通道
     */
    public MessageTemplate(MessageChannel defaultChannel) {
        this.defaultChannel = defaultChannel;
    }

    /**
     * 发送同步业务载荷，系统将自动完成信封封装
     *
     * @param payload 业务载荷对象
     * @return true 表示投递成功
     */
    public boolean send(Object payload) {
        return defaultChannel.send(wrap(payload));
    }

    /**
     * 在指定的执行器中异步发送业务载荷
     *
     * @param payload  业务载荷对象
     * @param executor 执行分发的线程池
     */
    public void sendAsync(Object payload, Executor executor) {
        defaultChannel.sendAsync(wrap(payload), executor);
    }

    /**
     * 异步发送业务载荷并接收结果回调
     *
     * @param payload  业务载荷对象
     * @param listener 分发结果回调监听器
     */
    public void sendAsync(Object payload, MessageChannel.SendListener listener) {
        defaultChannel.sendAsync(wrap(payload), listener);
    }

    /**
     * 内部信封包装逻辑，将各类对象统一适配为 Message 契约
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Message<?> wrap(Object payload) {
        if (payload instanceof Message) {
            return (Message<?>) payload;
        }
        return new GenericMessage(payload);
    }
}
