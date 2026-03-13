package com.team4u.framework.message.channel.mq;

import com.team4u.framework.base.util.IoUtil;
import com.team4u.framework.base.util.StringUtil;
import com.team4u.framework.policy.core.KeyedPolicyRegistry;

/**
 * 通道工厂注册持有者
 * <p>
 * 提供基于策略注册中心的工厂实例管理，通过逻辑配置标识动态分发不同中间件协议的通道创建请求。
 * 同时负责通过挂载停机钩子的方式，确保各生命周期通道在 JVM 退出时进行有序的资源清理。
 *
 * @author jay.wu
 */
public class MqChannelFactoryHolder {

    private final KeyedPolicyRegistry<String, MqChannelFactory> registry =
            new KeyedPolicyRegistry<>(MqChannelFactory.class);

    /**
     * 注册中间件驱动的具体工厂实例
     *
     * @param factory 工厂实例
     */
    public void register(MqChannelFactory factory) {
        registry.register(factory);
    }

    /**
     * 根据配置标识（如 "kafka.topic.example"）动态加载、创建并启动对应的消息通道
     *
     * @param configId 格式化的配置标识字符串，通常由协议前缀与目标目的地组成
     * @return 准备就绪的生命周期通道实例
     */
    public LifecycleMessageChannel createAndStart(String configId) {
        String protocol = StringUtil.subBefore(configId, ".", false);
        String destination = StringUtil.subAfter(configId, ".", false);

        MqChannelFactory factory = registry.get(protocol)
                .orElseThrow(() -> new IllegalArgumentException("Unsupported MQ protocol: [" + protocol + "]"));

        LifecycleMessageChannel channel = factory.create(destination);

        // 挂载资源释放钩子，保障在 JVM 关闭时进行必要的连接清理
        Runtime.getRuntime().addShutdownHook(new Thread(() -> IoUtil.close(channel)));

        // 启动逻辑通道，使其进入可分发状态
        channel.start();

        return channel;
    }
}
