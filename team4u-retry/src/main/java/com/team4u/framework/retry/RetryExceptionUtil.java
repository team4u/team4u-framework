package com.team4u.framework.retry;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * 重试异常工具类
 *
 * @author jay.wu
 */
public class RetryExceptionUtil {

    /**
     * 解包各种代理框架和异步框架产生的包装异常，提取根因
     *
     * @param ex 原始异常
     * @return 剥离后的核心异常
     */
    public static Throwable unwrap(Throwable ex) {
        Throwable cause = ex;
        while (cause != null) {
            if (cause instanceof CompletionException ||
                    cause instanceof ExecutionException ||
                    cause instanceof InvocationTargetException ||
                    cause instanceof UndeclaredThrowableException) {
                if (cause.getCause() != null) {
                    cause = cause.getCause();
                } else {
                    break;
                }
            } else {
                break;
            }
        }
        return cause != null ? cause : ex;
    }
}
