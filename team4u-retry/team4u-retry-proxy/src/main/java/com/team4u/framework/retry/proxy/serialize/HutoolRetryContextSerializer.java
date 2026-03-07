package com.team4u.framework.retry.proxy.serialize;

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

        // 检查参数是否标记了 @RetryIgnore 注解
        if (parameter != null && parameter.isAnnotationPresent(RetryIgnore.class)) {
            return null;
        }

        try {
            return JSONUtil.toJsonStr(arg);
        } catch (Exception e) {
            throw new RetrySerializationException(
                    "重试参数序列化失败。类型: " + arg.getClass().getName()
                            + ", 错误信息: " + e.getMessage(),
                    e);
        }
    }
}
