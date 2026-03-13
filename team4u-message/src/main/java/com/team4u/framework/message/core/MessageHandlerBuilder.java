package com.team4u.framework.message.core;

import com.team4u.framework.base.util.ThrowingConsumer;

import java.util.ArrayList;
import java.util.List;

/**
 * 业务处理器流式构建工具
 * <p>
 * 通过链式调用与函数式接口，支持快速、声明式地组装消息处理逻辑。
 * 该工具旨在简化轻量级消息消费逻辑的编写，规避繁冗的显式类定义。
 *
 * @author jay.wu
 */
public class MessageHandlerBuilder {

    private final List<MessageHandler<?>> handlers = new ArrayList<>();

    /**
     * 初始化构建流程
     */
    public static MessageHandlerBuilder create() {
        return new MessageHandlerBuilder();
    }

    /**
     * 绑定指定消息载荷类型与其对应的处理函数
     *
     * @param payloadType 消息载荷的类对象
     * @param func        函数式处理接口
     * @param <T>         业务载荷泛型
     */
    public <T> MessageHandlerBuilder onMessage(Class<T> payloadType, ThrowingConsumer<Message<T>> func) {
        handlers.add(new MessageHandler<T>() {
            @Override
            public Class<T> supportedPayloadType() {
                return payloadType;
            }

            @Override
            public void handle(Message<T> message) throws Exception {
                func.accept(message);
            }
        });
        return this;
    }

    /**
     * 生成当前构建器记录的所有业务处理器实例
     */
    public List<MessageHandler<?>> build() {
        return handlers;
    }
}
