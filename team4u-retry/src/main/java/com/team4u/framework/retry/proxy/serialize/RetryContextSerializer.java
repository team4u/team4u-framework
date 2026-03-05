package com.team4u.framework.retry.proxy.serialize;

import com.team4u.framework.retry.exception.RetrySerializationException;

import java.lang.reflect.Parameter;

/**
 * 重试上下文序列化器接口
 * <p>
 * 定义了如何将方法调用参数转换为可持久化的字符串快照。
 *
 * @author antigravity
 */
public interface RetryContextSerializer {

    /**
     * 将方法参数序列化为 JSON 字符串
     *
     * @param parameter 方法参数定义
     * @param arg       目标参数值
     * @return 序列化后的字符串。如果返回 null，代表该参数被忽略。
     * @throws RetrySerializationException 遇到严重序列化错误时抛出
     */
    String serialize(Parameter parameter, Object arg) throws RetrySerializationException;
}
