package com.team4u.framework.message.core;

import com.team4u.framework.base.util.TypeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import lombok.Getter;
import lombok.Setter;

import java.util.concurrent.Executor;

/**
 * 抽象消息处理器基类
 * <p>
 * 为业务开发提供极简的实现模板。
 * 通过反射技术自动推断泛型参数对应的载荷类型，并自动拆解消息信封，将业务载荷与元数据直接交付给实现类。
 * 同时支持为特定处理器指定独立的执行线程池，增强系统并发配置的灵活性。
 *
 * @param <T> 业务有效载荷类型
 * @author jay.wu
 */
public abstract class AbstractMessageHandler<T> implements MessageHandler<T> {

    protected final Logger log = LoggerFactory.getLogger(this.getClass());

    /**
     * 自动从类定义中推断出的业务载荷类型
     */
    @SuppressWarnings("unchecked")
    private final Class<T> payloadType = (Class<T>) TypeUtil.getTypeArgument(this.getClass());

    /**
     * 当前处理器专属的执行器，支持动态运行时配置
     */
    @Getter
    @Setter
    private Executor executor;

    @Override
    public Class<T> supportedPayloadType() {
        return payloadType;
    }

    @Override
    public final void handle(Message<T> message) throws Exception {
        long start = System.currentTimeMillis();
        try {
            // 直接执行拆包后的业务逻辑
            onMessage(message.getPayload(), message.getHeaders());
        } finally {
            if (log.isInfoEnabled()) {
                log.info("{} processed message [{}] cost [{}ms]",
                        this.getClass().getSimpleName(), message.getMessageType(), (System.currentTimeMillis() - start));
            }
        }
    }

    /**
     * 执行具体的业务处理逻辑
     * <p>
     * 子类只需关注载荷对象本身，无需感知信封结构。
     *
     * @param payload 业务载荷对象
     * @param headers 包含唯一标识等元数据的消息头
     * @throws Exception 处理逻辑中产生的异常
     */
    protected abstract void onMessage(T payload, MessageHeaders headers) throws Exception;
}
