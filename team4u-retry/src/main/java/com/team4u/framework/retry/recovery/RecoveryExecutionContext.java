package com.team4u.framework.retry.recovery;

/**
 * 恢复执行上下文。
 * <p>
 * 用于标记当前线程是否处于后端恢复执行阶段，避免代理重复进入重试链路。
 */
public final class RecoveryExecutionContext {

    private static final ThreadLocal<Boolean> RECOVERING = new ThreadLocal<Boolean>();

    private RecoveryExecutionContext() {
    }

    /**
     * 标记当前线程进入恢复态。
     */
    public static void enter() {
        RECOVERING.set(Boolean.TRUE);
    }

    /**
     * 清理当前线程恢复态标记。
     */
    public static void exit() {
        RECOVERING.remove();
    }

    /**
     * 当前线程是否处于恢复执行阶段。
     *
     * @return true 表示是恢复态
     */
    public static boolean isRecovering() {
        return Boolean.TRUE.equals(RECOVERING.get());
    }

    /**
     * 在恢复态中执行任务，并在结束后自动清理线程标记。
     *
     * @param action 目标动作
     * @throws Exception 执行异常
     */
    public static void run(CheckedRunnable action) throws Exception {
        enter();
        try {
            action.run();
        } finally {
            exit();
        }
    }

    @FunctionalInterface
    public interface CheckedRunnable {
        void run() throws Exception;
    }
}
