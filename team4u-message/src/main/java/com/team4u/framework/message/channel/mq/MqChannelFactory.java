package com.team4u.framework.message.channel.mq;

import com.team4u.framework.policy.KeyedPolicy;

/**
 * 消息队列通道工厂 SPI 契约
 * <p>
 * 基于策略标识（如协议类型）动态创建并配置具体的物理消息通道实例。
 * 实现类需通过 key() 声明支持的协议类型，配合策略注册中心实现不同中间件驱动的按需注入。
 *
 * @author jay.wu
 */
public interface MqChannelFactory extends KeyedPolicy<String> {

    /**
     * 根据物理目的地构建并初始化生命周期感知的消息通道
     *
     * @param destination 目标主题、队列或网络地址描述
     * @return 经过初始化的生命周期通道实例
     */
    LifecycleMessageChannel create(String destination);
}
