package com.team4u.framework.message.serialize;

import com.team4u.framework.message.core.MessageHeaders;
import com.team4u.framework.message.exception.MessagingException;

/**
 * 消息载荷序列化转换接口
 * <p>
 * 定义业务对象与物理传输介质（如二进制字节流或文本）之间的转换协议。
 * 用于保障消息在跨网络投递前后的数据完整性与类型一致性。
 *
 * @author jay.wu
 */
public interface MessageConverter {

    /**
     * 将业务对象实例序列化为底层传输格式
     *
     * @param payload 业务数据载荷
     * @param headers 消息元数据，可作为序列化策略选择的上下文依据
     * @return 序列化后的原始数据（通常为 byte[] 或 String）
     * @throws MessagingException 当数据转换过程产生不可控错误时抛出
     */
    Object toMessage(Object payload, MessageHeaders headers) throws MessagingException;

    /**
     * 将原始传输数据反序列化为特定的业务对象实例
     *
     * @param rawMessage  原始物理数据
     * @param targetClass 预期的目标业务类
     * @param <T>         业务类型泛型
     * @return 转换后的业务载荷实例
     * @throws MessagingException 当解析逻辑异常或类型不匹配时抛出
     */
    <T> T fromMessage(Object rawMessage, Class<T> targetClass) throws MessagingException;
}