package com.team4u.framework.retry.util;

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
        // 使用 IdentityHashMap 记录已处理异常，防止因循环引用导致的死循环
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());

        while (seen.add(cause)) {
            if (isWrapper(cause)) {
                Throwable nextCause = cause.getCause();
                if (nextCause != null) {
                    cause = nextCause;
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
     * 剥离包装异常，并在命中中断异常时恢复线程中断标记。
     */
    public static Throwable unwrapAndRestoreInterrupt(Throwable ex) {
        Throwable cause = unwrap(ex);
        if (cause instanceof InterruptedException) {
            Thread.currentThread().interrupt();
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
