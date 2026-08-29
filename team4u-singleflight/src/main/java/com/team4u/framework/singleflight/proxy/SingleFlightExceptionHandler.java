package com.team4u.framework.singleflight.proxy;

import java.lang.reflect.Method;

/**
 * 注解边界的组件异常转换器：把 {@link SingleFlightException} 转换为方法兼容的业务返回值。
 * <p>
 * 只处理组件异常（冲突 / 超时 / 重构失败 / 配置错误），不处理加载函数自己抛出的业务异常——
 * 后者始终原样上抛。未配置处理器时组件异常直接抛给调用方。
 * </p>
 *
 * @author jay.wu
 */
public interface SingleFlightExceptionHandler {

    /**
     * 处置组件异常并给出替代返回值。
     *
     * @param method            被拦截的方法
     * @param genericReturnType {@link Method#getGenericReturnType()}
     * @param throwable         组件异常（绝不可能是加载函数的业务异常）
     * @param arguments         参数名 → 参数值映射
     * @return 可赋值给 genericReturnType 的返回值
     * @throws Exception 处理器可抛出受检异常，拦截器会校验其与方法签名兼容
     */
    Object handle(Method method, java.lang.reflect.Type genericReturnType,
                  Throwable throwable, java.util.Map<String, Object> arguments) throws Exception;
}
