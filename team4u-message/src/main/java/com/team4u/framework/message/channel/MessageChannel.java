package com.team4u.framework.message.channel;

import com.team4u.framework.message.core.Message;
import com.team4u.framework.message.core.MessageHandler;

import java.util.Collection;
import java.util.concurrent.Executor;

/**
 * 消息传输通道契约
 * <p>
 * 提供消息在不同传输介质（如内存、MQ 等）上的统一发送与订阅抽象。
 * 为业务逻辑屏蔽了传输层的物理实现细节。
 *
 * @author jay.wu
 */
public interface MessageChannel {

    /**
     * 获取通道的唯一逻辑标识
     */
    String getName();

    /**
     * 同步发送消息
     *
     * @param message 待发送的消息信封
     * @return true 表示发送成功，false 表示发送失败
     */
    boolean send(Message<?> message);

    /**
     * 批量同步发送消息集合
     *
     * @param messages 待发送的消息列表
     */
    default void sendAll(Collection<? extends Message<?>> messages) {
        if (messages != null) {
            messages.forEach(this::send);
        }
    }

    /**
     * 发送异步消息的回调监听器
     */
    interface SendListener {
        /**
         * 发送成功回调
         */
        void onSucceeded(Message<?> message);

        /**
         * 发送失败或执行产生异常时的回调
         *
         * @param message   原始消息
         * @param exception 捕获到的异常，若无异常则可能为 null
         */
        void onFailed(Message<?> message, Exception exception);
    }

    /**
     * 发送异步消息并触发回调结果
     *
     * @param message  消息信封
     * @param listener 成功/失败的监听器接口
     */
    void sendAsync(Message<?> message, SendListener listener);

    /**
     * 在指定的线程池中异步发送消息
     *
     * @param message  消息信封
     * @param executor 指定运行此分发的执行器
     */
    default void sendAsync(Message<?> message, Executor executor) {
        if (executor == null) {
            send(message);
        } else {
            executor.execute(() -> send(message));
        }
    }

    /**
     * 向通道订阅消息处理逻辑
     *
     * @param handler 业务处理器实例
     */
    void subscribe(MessageHandler<?> handler);

    /**
     * 从通道取消消息订阅
     *
     * @param handler 待取消的业务处理器
     */
    void unsubscribe(MessageHandler<?> handler);
}
