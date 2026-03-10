package com.team4u.framework.retry.proxy.serialize;

import cn.hutool.core.convert.Convert;
import cn.hutool.json.JSONUtil;
import com.team4u.framework.retry.exception.RetrySerializationException;

import java.lang.reflect.Parameter;

/**
 * 基于 Hutool JSONUtil 实现的重试上下文序列化器
 * <p>
 * 将方法调用参数通过 Hutool 的 JSON 工具转换为 JSON 字符串。
 */
public class HutoolRetryContextSerializer implements RetryContextSerializer {

    /**
     * 全局默认实例
     */
    public static final HutoolRetryContextSerializer INSTANCE = new HutoolRetryContextSerializer();

    @Override
    public String serialize(Parameter parameter, Object arg) throws RetrySerializationException {
        if (arg == null) {
            return null;
        }

        try {
            return JSONUtil.toJsonStr(arg);
        } catch (Exception e) {
            throw new RetrySerializationException(
                    "Failed to serialize retry arguments. Type: " + arg.getClass().getName()
                            + ", Error: " + e.getMessage(),
                    e);
        }
    }

    @Override
    public Object deserialize(Class<?> type, String json) throws RetrySerializationException {
        if (json == null) {
            return null;
        }

        try {
            // 处理基本类型及其包装类、字符串等简单类型
            if (isSimpleType(type)) {
                Object value = JSONUtil.parseArray("[" + json + "]").get(0);
                if (type == char.class || type == Character.class) {
                    String text = Convert.toStr(value);
                    return text == null || text.isEmpty() ? '\0' : text.charAt(0);
                }
                return Convert.convert(type, value);
            }
            // 处理复杂对象
            return JSONUtil.toBean(json, type);
        } catch (Exception e) {
            throw new RetrySerializationException(
                    "Failed to deserialize retry arguments. Type: " + type.getName()
                            + ", Error: " + e.getMessage(),
                    e);
        }
    }

    /**
     * 判断是否为简单类型。
     * <p>
     * 包括基本类型及其包装类、字符串。
     *
     * @param type 待检查的类型
     * @return 如果是简单类型则返回 true
     */
    private boolean isSimpleType(Class<?> type) {
        return type.isPrimitive()
                || type == String.class
                || type == Boolean.class
                || type == Byte.class
                || type == Short.class
                || type == Integer.class
                || type == Long.class
                || type == Float.class
                || type == Double.class
                || type == Character.class;
    }
}
