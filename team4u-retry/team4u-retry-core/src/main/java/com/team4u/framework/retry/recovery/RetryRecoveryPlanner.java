package com.team4u.framework.retry.recovery;

import com.team4u.framework.retry.RetryPolicy;
import com.team4u.framework.retry.backend.RetryCloseOutcome;
import com.team4u.framework.retry.backend.RetryCloseReason;
import com.team4u.framework.retry.backend.RetryTaskSnapshot;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

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
     * @param snapshot 任务快照，包含已执行次数等状态信息
     * @param policy   重试策略，定义了重试间隔、最大次数等规则
     * @param cause    导致当前尝试失败的异常信息
     * @return 执行计划，描述是继续进行下一轮重试还是关闭任务
     */
    public Plan plan(RetryTaskSnapshot snapshot, RetryPolicy policy, Throwable cause) {
        int currentAttempt = snapshot.getExecutedAttempts();

        RetryCloseReason closeReason = classifyCloseReason(policy, currentAttempt, cause);
        if (closeReason != null) {
            return Plan.close(RetryCloseOutcome.FAILED, closeReason, cause == null ? null : cause.toString());
        }

        long delay = policy.getDelayMillis(currentAttempt + 1);
        return Plan.retry(delay);
    }

    /**
     * 根据策略对失败原因进行分类，判断是否应结束重试
     *
     * @param policy           重试策略
     * @param executedAttempts 已执行的尝试次数
     * @param cause            失败异常
     * @return 如果应结束则返回关闭原因，否则返回 null 表示可继续重试
     */
    private RetryCloseReason classifyCloseReason(RetryPolicy policy, int executedAttempts, Throwable cause) {
        if (policy.getMaxAttempts() != -1 && executedAttempts >= policy.getMaxAttempts()) {
            return RetryCloseReason.RETRY_EXHAUSTED;
        }
        if (!policy.canRetry(executedAttempts, cause)) {
            return RetryCloseReason.ABORTED_BY_POLICY;
        }
        return null;
    }

    /**
     * 重试决策方案
     * <p>
     * 封装了重试决策的结果，包括是否继续重试、重试延迟、任务关闭原因等信息。
     */
    @Getter
    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    public static class Plan {
        /**
         * 重试延迟时间（毫秒）
         */
        private final long delayMillis;
        /**
         * 任务结束后的最终结果状态
         */
        private final RetryCloseOutcome outcome;
        /**
         * 任务关闭的具体原因
         */
        private final RetryCloseReason reason;
        /**
         * 失败时的错误消息
         */
        private final String errorMessage;

        /**
         * 创建重试计划
         *
         * @param delayMillis 重试延迟时间（毫秒）
         * @return 重试计划实例
         */
        public static Plan retry(long delayMillis) {
            return new Plan(delayMillis, null, null, null);
        }

        /**
         * 创建关闭计划
         *
         * @param outcome      结束状态
         * @param reason       关闭原因
         * @param errorMessage 错误消息
         * @return 关闭计划实例
         */
        public static Plan close(RetryCloseOutcome outcome, RetryCloseReason reason, String errorMessage) {
            return new Plan(0L, outcome, reason, errorMessage);
        }

        /**
         * 判断当前计划是否为继续重试
         *
         * @return 如果为 true 表示需要继续重试，false 表示任务应关闭
         */
        public boolean isRetry() {
            return outcome == null;
        }
    }
}
