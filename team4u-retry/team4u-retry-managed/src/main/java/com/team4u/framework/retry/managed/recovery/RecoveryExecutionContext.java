package com.team4u.framework.retry.managed.recovery;

/**
 * 恢复执行上下文
 * <p>
 * 用于标识当前线程是否处于后端任务恢复执行阶段，防止代理逻辑重复触发重试。
 * 该标记仅覆盖当前同步线程内的恢复阶段，不会跨线程或异步边界传播。
 */
public final class RecoveryExecutionContext {

    private static final ThreadLocal<Boolean> RECOVERING = new ThreadLocal<Boolean>();

    private RecoveryExecutionContext() {
    }

    /**
     * 进入恢复状态
     */
    public static void enter() {
        RECOVERING.set(Boolean.TRUE);
    }

    /**
     * 退出恢复状态，清理线程上下文
     */
    public static void exit() {
        RECOVERING.remove();
    }

    /**
     * 检查当前线程是否处于恢复执行阶段
     *
     * @return 为 true 表示处于恢复状态
     */
    public static boolean isRecovering() {
        return Boolean.TRUE.equals(RECOVERING.get());
    }

    /**
     * 在恢复上下文中执行指定动作，并在完成后自动清理状态。
     * 上下文只在当前线程内有效，不表达异步传播或嵌套层级语义。
     *
     * @param action 待执行的任务
     * @throws Exception 执行过程中的异常
     */
    public static void run(CheckedRunnable action) throws Exception {
        enter();
        try {
            action.run();
        } finally {
            exit();
        }
    }

    /**
     * 支持抛出受检异常的可执行接口
     */
    @FunctionalInterface
    public interface CheckedRunnable {
        void run() throws Exception;
    }
}
