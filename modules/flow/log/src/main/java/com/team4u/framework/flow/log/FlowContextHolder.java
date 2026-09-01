package com.team4u.framework.flow.log;

import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * 流程上下文当前线程持有器。
 *
 * <p>用于在流程执行生命周期中为日志观察者（{@link FlowLoggingObserver}）提供当前流转的业务上下文对象引用。</p>
 *
 * @author jay.wu
 */
public final class FlowContextHolder {

    private static final ThreadLocal<Object> CONTEXT_HOLDER = new ThreadLocal<Object>();

    private FlowContextHolder() {
    }

    /**
     * 绑定当前线程的流程上下文对象。
     *
     * @param context 业务上下文对象
     */
    public static void set(Object context) {
        CONTEXT_HOLDER.set(context);
    }

    /**
     * 获取当前线程绑定的流程上下文对象。
     *
     * @return 业务上下文对象，若未设置则返回 null
     */
    public static Object get() {
        return CONTEXT_HOLDER.get();
    }

    /**
     * 获取指定类型的当前流程上下文对象。
     *
     * @param <T>  目标上下文类型
     * @param type 目标类型 Class
     * @return 业务上下文对象，若未设置或类型不匹配则返回 null
     */
    @SuppressWarnings("unchecked")
    public static <T> T get(Class<T> type) {
        Objects.requireNonNull(type, "type must not be null");
        Object obj = CONTEXT_HOLDER.get();
        if (obj != null && type.isInstance(obj)) {
            return (T) obj;
        }
        return null;
    }

    /**
     * 清理当前线程绑定的流程上下文对象，防止内存泄漏。
     */
    public static void remove() {
        CONTEXT_HOLDER.remove();
    }

    /**
     * 在绑定的上下文生命周期内执行给定的业务逻辑，并在执行完毕后自动清理。
     *
     * @param <T>      返回值类型
     * @param context  业务上下文对象
     * @param callable 待执行逻辑
     * @return 执行结果
     * @throws Exception 当执行抛出异常时原样抛出
     */
    public static <T> T runWith(Object context, Callable<T> callable) throws Exception {
        Objects.requireNonNull(callable, "callable must not be null");
        set(context);
        try {
            return callable.call();
        } finally {
            remove();
        }
    }

    /**
     * 在绑定的上下文生命周期内执行给定的无返回值业务逻辑，并在执行完毕后自动清理。
     *
     * @param context  业务上下文对象
     * @param runnable 待执行逻辑
     */
    public static void runWith(Object context, Runnable runnable) {
        Objects.requireNonNull(runnable, "runnable must not be null");
        set(context);
        try {
            runnable.run();
        } finally {
            remove();
        }
    }
}
