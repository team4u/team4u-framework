package com.team4u.framework.message.core;

import java.io.Serializable;

/**
 * 消息信封契约
 * <p>
 * 为消息载荷提供标准化的封装结构。消息在流转过程中由信封携带，确保业务数据与描述性属性（元数据）的统一传输。
 *
 * @param <T> 业务载荷的具体类型
 * @author jay.wu
 */
public interface Message<T> extends Serializable {

    /**
     * 获取封装的业务载荷对象
     *
     * @return 具体业务数据实例
     */
    T getPayload();

    /**
     * 获取消息的元数据集合
     *
     * @return 包含各类业务描述信息的 Headers 容器
     */
    MessageHeaders getHeaders();

    /**
     * 快捷访问消息唯一标识
     */
    default String getId() {
        return getHeaders().getId();
    }

    /**
     * 快捷访问消息产生时间
     */
    default Long getTimestamp() {
        return getHeaders().getTimestamp();
    }

    /**
     * 快捷访问业务类型描述符
     */
    default String getMessageType() {
        return getHeaders().getMessageType();
    }
}
