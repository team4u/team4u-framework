package com.team4u.framework.retry;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * 重试异常工具类
 *
 * @author jay.wu
 */
public class RetryExceptionUtil {

    /**
     * 最大剥离深度，防止异常链过深导致的性能问题
     */
    private static final int MAX_UNWRAP_DEPTH = 10;

    /**
     * 解包各种代理框架和异步框架产生的包装异常，提取根因
     *
     * @param ex 原始异常
     * @return 剥离后的核心异常
     */
    public static Throwable unwrap(Throwable ex) {
        if (ex == null) {
            return null;
        }

        Throwable cause = ex;
        int depth = 0;
        // 使用 IdentityHashMap 防止异常对象重写 hashCode/equals 导致的判断失真
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());

        while (depth < MAX_UNWRAP_DEPTH && seen.add(cause)) {
            if (isWrapper(cause)) {
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
        return cause;
    }

    private static boolean isWrapper(Throwable cause) {
        return cause instanceof CompletionException ||
                cause instanceof ExecutionException ||
                cause instanceof InvocationTargetException ||
                cause instanceof UndeclaredThrowableException;
    }
}
