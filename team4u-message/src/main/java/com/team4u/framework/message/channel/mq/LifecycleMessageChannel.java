package com.team4u.framework.message.channel.mq;

import com.team4u.framework.message.channel.MessageChannel;

import java.io.Closeable;

/**
 * 带有生命周期管理能力的通道接口
 * <p>
 * 为消息通道补充了生命周期控制能力，支持显式的服务开启与物理资源的安全释放。
 * 建议在涉及网络通信或连接池管理的通道实现中使用。
 *
 * @author jay.wu
 */
public interface LifecycleMessageChannel extends MessageChannel, Closeable {

    /**
     * 开启通道服务逻辑，执行连接建立、监听启动或资源预热等前置操作
     */
    void start();
}
