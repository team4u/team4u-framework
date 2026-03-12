package com.team4u.framework.retry.proxy.serialize;

import com.team4u.framework.retry.proxy.serialize.RetrySerializationException;

import java.lang.reflect.Type;

/**
 * 重试上下文序列化器
 * <p>
 * 定义将方法调用参数转换为持久化字符串快照的标准行为，
 * 用于在持久化重试模式下存储和恢复方法调用现场。
 */
public interface RetryContextSerializer {

    /**
     * 将方法参数序列化为字符串
     *
     * @param arg 目标参数值
     * @return 序列化后的字符串。返回 null 表示跳过该参数（即该参数不参与持久化恢复）。
     * @throws RetrySerializationException 遇到无法处理的序列化错误时抛出
     */
    String serialize(Object arg) throws RetrySerializationException;

    /**
     * 将序列化的 JSON 字符串还原为对象
     *
     * @param declaredType 目标参数声明类型，允许携带泛型信息
     * @param json         序列化后的字符串快照
     * @return 反序列化后的对象实例
     * @throws RetrySerializationException 遇到无法处理的序列化错误时抛出
     */
    Object deserialize(Type declaredType, String json) throws RetrySerializationException;
}
