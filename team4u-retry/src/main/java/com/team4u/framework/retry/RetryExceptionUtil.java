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
     * 最大剥离深度，防止异常链循环引用导致的死循环
     */
    private static final int MAX_UNWRAP_DEPTH = 10;

    /**
     * 解包各种代理框架和异步框架产生的包装异常，提取根因
     *
     * @param ex 原始异常
     * @return 剥离后的核心异常
     */
    public static Throwable unwrap(Throwable ex) {
        Throwable cause = ex;
        int depth = 0;
        while (cause != null && depth < MAX_UNWRAP_DEPTH) {
            if (cause instanceof CompletionException ||
                    cause instanceof ExecutionException ||
                    cause instanceof InvocationTargetException ||
                    cause instanceof UndeclaredThrowableException) {
                Throwable nextCause = cause.getCause();
                if (nextCause != null) {
                    cause = nextCause;
                    depth++;
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
