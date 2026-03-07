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
 * <p>
 * 提供异常链分析与根因提取功能。
 */
public class RetryExceptionUtil {

    /**
     * 最大剥离深度，防止无限递归或过深的异常链影响性能
     */
    private static final int MAX_UNWRAP_DEPTH = 10;

    /**
     * 剥离包装异常，提取原始业务根因
     * <p>
     * 递归解析异步框架、代理机制产生的包装类，直到发现核心异常或达到深度限制。
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
        // 使用 IdentityHashMap 记录已处理异常，防止因循环引用导致的死循环
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

    /**
     * 判断指定异常是否为已知的包装类型
     */
    private static boolean isWrapper(Throwable cause) {
        return cause instanceof CompletionException ||
                cause instanceof ExecutionException ||
                cause instanceof InvocationTargetException ||
                cause instanceof UndeclaredThrowableException;
    }
}

