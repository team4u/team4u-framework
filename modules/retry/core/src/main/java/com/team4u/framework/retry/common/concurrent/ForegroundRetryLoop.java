package com.team4u.framework.retry.common.concurrent;

import com.team4u.framework.base.util.ThreadUtil;
import com.team4u.framework.retry.api.RetryPolicy;
import com.team4u.framework.retry.common.util.RetryExceptionUtil;

/**
 * 前台（调用者线程）同步重试循环
 * <p>
 * 收敛 INLINE（{@link com.team4u.framework.retry.inline.DefaultInlineRetryClient}）与
 * MANAGED（{@link com.team4u.framework.retry.managed.client.DefaultManagedRetryClient}）
 * 两个客户端重复实现的「执行 - 失败计数 - 策略判定 - 退避休眠」循环骨架：
 * <ul>
 *     <li>每次尝试的异常先经 {@link #normalize(Throwable)} 还原根因；</li>
 *     <li>{@link Error}（如 OutOfMemoryError）属系统级严重错误，不进入重试循环；</li>
 *     <li>休眠语义为「中断优先」：退避被中断时立即结束循环并以
 *     {@link InterruptedException} 报告（中断状态已恢复），由调用方决定如何收尾；</li>
 *     <li>循环推进交由调用方的 {@link Listener} 回调完成，使两个客户端得以在
 *     「重试耗尽即抛出」（INLINE）与「达到前台上限即移交后台」（MANAGED）之间
 *     保留各自的对外语义。</li>
 * </ul>
 *
 * @author team4u
 */
public final class ForegroundRetryLoop {

    private ForegroundRetryLoop() {
    }

    /**
     * 前台重试循环监听器：在循环关键节点回调宿主客户端
     *
     * @param <T> 任务返回值类型
     * @param <R> 循环最终结果类型（INLINE 为 T，MANAGED 为 ManagedSubmitResult&lt;T&gt;）
     */
    public interface Listener<T, R> {

        /**
         * 本次尝试执行成功
         *
         * @param result             业务返回值
         * @param failedAttemptsSoFar 截至成功前已失败的尝试次数
         * @return 循环的最终结果（宿主可原样返回或包装）
         */
        R onSuccess(T result, int failedAttemptsSoFar);

        /**
         * 本次尝试失败且策略判定不可再重试（重试耗尽或命中终止条件）
         *
         * @param cause              规范化后的失败根因
         * @param failedAttemptsSoFar 截至当前已失败的尝试次数
         * @return 循环的最终结果（INLINE 抛出异常，MANAGED 返回 Failed）
         */
        R onRetryExhausted(Throwable cause, int failedAttemptsSoFar);

        /**
         * 本次尝试失败，退避休眠被中断
         * <p>
         * 中断状态已由休眠工具恢复，宿主收尾后应尽快结束。
         *
         * @param interrupted        中断异常
         * @param failedAttemptsSoFar 截至当前已失败的尝试次数
         * @return 循环的最终结果
         */
        R onInterrupted(InterruptedException interrupted, int failedAttemptsSoFar);

        /**
         * 本次尝试失败且策略允许重试，但已达到前台尝试上限，应停止前台循环
         *
         * @param cause              规范化后的失败根因
         * @param failedAttemptsSoFar 截至当前已失败的尝试次数
         * @return 循环的最终结果（MANAGED 由此移交后台调度）
         */
        R onForegroundBudgetExhausted(Throwable cause, int failedAttemptsSoFar);

        /**
         * @return 前台允许的最大尝试次数（含首次执行）；返回 -1 表示无前台次数限制
         */
        int maxForegroundExecutions();
    }

    /**
     * 执行前台同步重试循环
     *
     * @param policy   重试策略
     * @param task     业务任务
     * @param listener 循环监听器
     * @param <T>      任务返回值类型
     * @param <R>      循环最终结果类型
     * @return 监听器回调产生的最终结果
     */
    public static <T, R> R execute(
            RetryPolicy policy, CallableTask<T> task, Listener<T, R> listener) {
        int maxForegroundExecutions = listener.maxForegroundExecutions();
        int failedAttemptsSoFar = 0;

        while (true) {
            T result;
            try {
                result = task.call();
            } catch (Throwable ex) {
                failedAttemptsSoFar++;
                Throwable cause = normalize(ex);

                // Error 类型（如 OutOfMemoryError）属于严重的系统级异常，
                // 不应进入重试循环，直接向上抛出
                if (cause instanceof Error) {
                    throw (Error) cause;
                }

                // 这里只在失败后进入 canRetry，因此传入的是“截至当前已失败的次数”
                if (!policy.canRetry(failedAttemptsSoFar, cause)) {
                    return listener.onRetryExhausted(cause, failedAttemptsSoFar);
                }

                // 未达到前台尝试上限，则退避后继续
                if (maxForegroundExecutions < 0 || failedAttemptsSoFar < maxForegroundExecutions) {
                    // 中断优先：退避被中断时立即结束循环（中断状态已恢复）
                    if (!ThreadUtil.sleepQuietly(policy.getDelayMillis(failedAttemptsSoFar))) {
                        return listener.onInterrupted(
                                new InterruptedException("retry backoff interrupted"),
                                failedAttemptsSoFar);
                    }
                    continue;
                }

                // 达到前台尝试上限（仅 MANAGED 前台路径会走到）
                return listener.onForegroundBudgetExhausted(cause, failedAttemptsSoFar);
            }
            return listener.onSuccess(result, failedAttemptsSoFar);
        }
    }

    /**
     * 可抛出任意异常的业务任务
     *
     * @param <T> 返回值类型
     */
    public interface CallableTask<T> {

        /**
         * @return 业务返回值
         * @throws Throwable 业务异常
         */
        T call() throws Throwable;
    }

    /**
     * 规范化异常。
     * <p>
     * 将各种包装后的异常还原为其原始原因，并正确维护线程中断状态。
     */
    private static Throwable normalize(Throwable ex) {
        if (ex instanceof InterruptedException) {
            Thread.currentThread().interrupt();
            return ex;
        }
        if (ex instanceof Error) {
            return ex;
        }
        return RetryExceptionUtil.unwrapAndRestoreInterrupt(ex);
    }
}
