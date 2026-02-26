package com.team4u.framework.message.core;

import cn.hutool.core.util.IdUtil;
import lombok.Getter;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/**
 * 消息元数据容器
 * <p>
 * 封装了消息的描述性信息，包括唯一标识、生成时间、业务类型以及回复路径等。
 * 继承自 HashMap 以支持灵活的业务扩展属性，并提供标准属性的便捷访问接口。
 *
 * @author jay.wu
 */
@Getter
public class MessageHeaders extends HashMap<String, Object> implements Serializable {

    /**
     * 消息唯一标识的属性名
     */
    public static final String ID = "id";
    /**
     * 消息产生时间戳的属性名
     */
    public static final String TIMESTAMP = "timestamp";
    /**
     * 消息业务类型的属性名
     */
    public static final String MESSAGE_TYPE = "message-type";
    /**
     * 指定回复通道的属性名
     */
    public static final String REPLY_CHANNEL = "reply-channel";
    private static final long serialVersionUID = 1L;

    public MessageHeaders() {
        super();
        initDefaultHeaders();
    }

    public MessageHeaders(Map<String, Object> headers) {
        super(headers);
        initDefaultHeaders();
    }

    /**
     * 初始化基础元数据，确保消息具备可追踪的标识与时间属性
     */
    public final void initDefaultHeaders() {
        this.putIfAbsent(ID, IdUtil.fastSimpleUUID());
        this.putIfAbsent(TIMESTAMP, System.currentTimeMillis());
    }

    /**
     * 获取当前消息的唯一标识
     */
    public String getId() {
        return (String) get(ID);
    }

    /**
     * 获取消息产生的毫秒级时间戳
     */
    public Long getTimestamp() {
        return (Long) get(TIMESTAMP);
    }

    /**
     * 获取消息的业务类型标识
     */
    public String getMessageType() {
        return (String) get(MESSAGE_TYPE);
    }

    /**
     * 获取建议的回复通道名称
     */
    public String getReplyChannel() {
        return (String) get(REPLY_CHANNEL);
    }
}
