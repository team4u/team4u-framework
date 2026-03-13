package com.team4u.framework.retry.proxy.serialize;

import com.team4u.framework.serializer.json.JsonUtil;

import java.lang.reflect.Type;

/**
 * 基于 Jackson 实现的重试上下文序列化器。
 * <p>
 * 该序列化器利用 Jackson 的 ObjectMapper 处理参数的持久化与恢复，
 * 支持复杂的泛型、POJO 以及基础类型转换。
 *
 * @author jay.wu
 */
public class JacksonRetryContextSerializer implements RetryContextSerializer {

    /**
     * 该实例线程安全，提供全局共享访问。
     */
    public static final JacksonRetryContextSerializer INSTANCE = new JacksonRetryContextSerializer();

    @Override
    public String serialize(Object arg) throws RetrySerializationException {
        if (arg == null) {
            return null;
        }

        try {
            return JsonUtil.toJsonStr(arg);
        } catch (Exception e) {
            throw new RetrySerializationException(
                    "序列化重试参数失败。类型: " + arg.getClass().getName()
                            + ", 错误原因: " + e.getMessage(),
                    e);
        }
    }

    @Override
    public Object deserialize(Type declaredType, String json) throws RetrySerializationException {
        if (json == null) {
            return null;
        }

        try {
            return JsonUtil.toBean(json, declaredType);
        } catch (Exception e) {
            throw new RetrySerializationException(
                    "反序列化重试参数失败。目标类型: " + declaredType.getTypeName()
                            + ", 错误原因: " + e.getMessage(),
                    e);
        }
    }
}
