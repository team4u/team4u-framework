package com.team4u.framework.message.core;

import com.team4u.framework.base.util.Assert;
import lombok.Getter;
import lombok.ToString;

import java.util.Map;

/**
 * 通用消息信封实现
 * <p>
 * 提供了消息信封的标准构造逻辑。采用不可变设计，确保消息在分发与流转过程中状态稳定。
 * 在构造过程中，若未显式指定业务类型，将自动根据载荷对象的类型名称进行推导。
 *
 * @param <T> 业务载荷的具体类型
 * @author jay.wu
 */
@Getter
@ToString
public class GenericMessage<T> implements Message<T> {

    private static final long serialVersionUID = 1L;

    /**
     * 业务载荷实例
     */
    private final T payload;

    /**
     * 消息元数据容器
     */
    private final MessageHeaders headers;

    /**
     * 构建仅包含业务载荷的消息，元数据将采用默认初始化策略
     *
     * @param payload 业务数据对象
     */
    public GenericMessage(T payload) {
        this(payload, new MessageHeaders());
    }

    /**
     * 根据载荷与元数据映射构建完整消息
     *
     * @param payload 业务数据对象
     * @param headers 包含元数据的映射表，会自动补全基础属性
     */
    public GenericMessage(T payload, Map<String, Object> headers) {
        Assert.notNull(payload, "消息载荷不能为空");
        this.payload = payload;

        if (headers instanceof MessageHeaders) {
            this.headers = (MessageHeaders) headers;
        } else {
            this.headers = new MessageHeaders(headers);
        }

        if (!this.headers.containsKey(MessageHeaders.MESSAGE_TYPE)) {
            this.headers.put(MessageHeaders.MESSAGE_TYPE, payload.getClass().getName());
        }
    }
}
