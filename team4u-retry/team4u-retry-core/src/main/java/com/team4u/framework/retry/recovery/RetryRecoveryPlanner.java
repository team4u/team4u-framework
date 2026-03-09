package com.team4u.framework.retry.recovery;

import com.team4u.framework.retry.RetryExecutionContext;
import com.team4u.framework.retry.backend.RetryCloseReason;
import com.team4u.framework.retry.policy.RetryPolicy;
import lombok.Data;

/**
 * 重试恢复计划器
 * <p>
 * 负责根据任务当前的执行状态（快照）和配置的重试策略，
 * 计算并生成下一步的动作方案（重试或结束）。
 *
 * @author jay.wu
 */
public class RetryRecoveryPlanner {

    /**
     * 根据任务快照和重试策略生成执行计划
     *
     * @param context         执行上下文，包含当前尝试次数和异常原因
     * @param policy          重试策略，定义了重试间隔、最大次数等规则
     * @param localAttempts   本地进程内的最大尝试次数
     * @param hasRetryBackend 是否开启了持久化后端
     * @return 执行计划，描述是继续进行下一轮重试、移交后端还是关闭任务
     */
    public Plan plan(RetryExecutionContext<?> context,
                     RetryPolicy policy,
                     int localAttempts,
                     boolean hasRetryBackend) {
        int executedAttempts = context.getExecutedAttempts();
        Throwable cause = context.getLastError();

        // 1. 检查是否应终止（分类关闭原因）
        RetryCloseReason closeReason = classifyCloseReason(policy, executedAttempts, cause);
        if (closeReason != null) {
            return Plan.close(closeReason, cause == null ? null : cause.toString());
        }

        // 2. 检查是否应在内存中重试
        if (executedAttempts < localAttempts) {
            long delay = policy.getDelayMillis(executedAttempts + 1);
            return Plan.retryInMemory(delay);
        }

        // 3. 检查是否应移交给持久化后端
        if (hasRetryBackend && (policy.getMaxAttempts() == -1 || executedAttempts < policy.getMaxAttempts())) {
            long delay = policy.getDelayMillis(executedAttempts + 1);
            return Plan.handoffToBackend(delay);
        }

        // 4. 默认回退：最终失败
        return Plan.close(RetryCloseReason.ABORTED_BY_POLICY, cause == null ? null : cause.toString());
    }

    /**
     * 根据策略对失败原因进行分类，判断是否应结束重试
     *
     * @param policy           重试策略
     * @param executedAttempts 已执行的尝试次数
     * @param cause            失败异常
     * @return 如果应结束则返回关闭原因，否则返回 null 表示可继续重试
     */
    public RetryCloseReason classifyCloseReason(RetryPolicy policy, int executedAttempts, Throwable cause) {
        // 关键系统异常或中断，立即终止重试并标记为策略中断
        if (cause instanceof Error || cause instanceof InterruptedException) {
            return RetryCloseReason.ABORTED_BY_POLICY;
        }
        // 达到最大重试次数限制
        if (policy.getMaxAttempts() != -1 && executedAttempts >= policy.getMaxAttempts()) {
            return RetryCloseReason.RETRY_EXHAUSTED;
        }
        // 策略根据异常类型或自定义条件判定不再重试
        if (!policy.canRetry(executedAttempts, cause)) {
            return RetryCloseReason.ABORTED_BY_POLICY;
        }
        // 返回 null 表示尚未达到终态，可以继续重试
        return null;
    }

    /**
     * 重试决策方案
     * <p>
     * 封装了重试决策的结果，包括决策类型、相关参数（延迟、关闭原因等）。
     */
    @Data
    public static class Plan {

        private final Type type;
        /**
         * 延迟时间（毫秒）
         */
        private final long delayMillis;
        /**
         * 任务关闭的原因
         */
        private final RetryCloseReason reason;
        /**
         * 失败提示信息
         */
        private final String errorMessage;

        public static Plan retryInMemory(long delayMillis) {
            return new Plan(Type.RETRY_IN_MEMORY, delayMillis, null, null);
        }

        public static Plan handoffToBackend(long delayMillis) {
            return new Plan(Type.HANDOFF_TO_BACKEND, delayMillis, null, null);
        }

        public static Plan close(RetryCloseReason reason, String errorMessage) {
            return new Plan(Type.CLOSE, 0, reason, errorMessage);
        }

        public enum Type {
            /**
             * 内存中重试
             */
            RETRY_IN_MEMORY,
            /**
             * 移交给后端持久化
             */
            HANDOFF_TO_BACKEND,
            /**
             * 任务关闭
             */
            CLOSE
        }
    }
}