package com.team4u.framework.message.channel.jvm;

import com.team4u.framework.base.util.Assert;
import com.team4u.framework.message.channel.MessageChannel;
import com.team4u.framework.message.core.Message;
import com.team4u.framework.message.core.MessageDispatcher;
import com.team4u.framework.message.core.MessageHandler;

/**
 * JVM 进程内内存通道实现
 * <p>
 * 提供高性能、低延迟的本地事件分发能力。其底层基于本地分发引擎实现，
 * 适用于进程内部逻辑解耦及单机事件通知场景。
 *
 * @author jay.wu
 */
public class JvmMessageChannel implements MessageChannel {

    private final String name;
    private final MessageDispatcher dispatcher;

    /**
     * @param name       通道标识
     * @param dispatcher 关联的本地消息分发引擎
     */
    public JvmMessageChannel(String name, MessageDispatcher dispatcher) {
        Assert.notBlank(name, "通道名称不能为空");
        Assert.notNull(dispatcher, "分发引擎不能为空");
        this.name = name;
        this.dispatcher = dispatcher;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public boolean send(Message<?> message) {
        return dispatcher.dispatch(message);
    }

    @Override
    public void sendAsync(Message<?> message, SendListener listener) {
        try {
            boolean success = send(message);
            if (listener != null) {
                if (success) {
                    listener.onSucceeded(message);
                } else {
                    listener.onFailed(message, null);
                }
            }
        } catch (Exception e) {
            if (listener != null) {
                listener.onFailed(message, e);
            }
        }
    }

    @Override
    public void subscribe(MessageHandler<?> handler) {
        dispatcher.addHandler(handler);
    }

    @Override
    public void unsubscribe(MessageHandler<?> handler) {
        dispatcher.removeHandler(handler);
    }
}
