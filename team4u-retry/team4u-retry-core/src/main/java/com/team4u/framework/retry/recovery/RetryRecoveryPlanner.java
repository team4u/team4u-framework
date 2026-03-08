package com.team4u.framework.retry.recovery;

import com.team4u.framework.retry.RetryPolicy;
import com.team4u.framework.retry.backend.RetryCloseOutcome;
import com.team4u.framework.retry.backend.RetryCloseReason;
import com.team4u.framework.retry.backend.RetryTaskSnapshot;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 重试恢复决策规划者
 * <p>
 * 职责：根据任务快照和重试策略，计算下一次执行或最终失败的决策。
 *
 * @author jay.wu
 */
public class RetryRecoveryPlanner {

    /**
     * 评估下一次重试决策
     *
     * @param snapshot 任务快照
     * @param policy   重试策略
     * @param cause    引发当前失败的异常
     * @return 决策方案
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

    private RetryCloseReason classifyCloseReason(RetryPolicy policy, int executedAttempts, Throwable cause) {
        if (policy.getMaxAttempts() != -1 && executedAttempts >= policy.getMaxAttempts()) {
            return RetryCloseReason.RETRY_EXHAUSTED;
        }
        if (!policy.canRetry(executedAttempts, cause)) {
            return RetryCloseReason.ABORTED_BY_POLICY;
        }
        return null;
    }

    @Getter
    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    public static class Plan {
        private final long delayMillis;
        private final RetryCloseOutcome outcome;
        private final RetryCloseReason reason;
        private final String errorMessage;

        public static Plan retry(long delayMillis) {
            return new Plan(delayMillis, null, null, null);
        }

        public static Plan close(RetryCloseOutcome outcome, RetryCloseReason reason, String errorMessage) {
            return new Plan(0L, outcome, reason, errorMessage);
        }

        public boolean isRetry() {
            return outcome == null;
        }
    }
}
