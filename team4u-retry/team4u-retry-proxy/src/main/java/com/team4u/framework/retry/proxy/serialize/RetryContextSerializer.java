package com.team4u.framework.retry.proxy.serialize;

import com.team4u.framework.retry.exception.RetrySerializationException;

import java.lang.reflect.Parameter;

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
     * @param parameter 对应的反射参数定义，可用于读取注解（如 {@link RetryIgnore}）
     * @param arg       目标参数值
     * @return 序列化后的字符串。返回 null 表示跳过该参数（即该参数不参与持久化恢复）。
     * @throws RetrySerializationException 遇到无法处理的序列化错误时抛出
     */
    String serialize(Parameter parameter, Object arg) throws RetrySerializationException;
}
