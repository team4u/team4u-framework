package com.team4u.framework.retry.recovery;

import com.team4u.framework.retry.RetryPolicy;
import com.team4u.framework.retry.backend.RetryTaskSnapshot;
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

        // 检查策略是否允许继续
        if (!policy.canRetry(currentAttempt, cause)) {
            return new Plan(Decision.FAIL_TERMINAL, 0);
        }

        // 计算下次延迟
        long delay = policy.getDelayMillis(currentAttempt + 1);
        return new Plan(Decision.RETRY, delay);
    }

    public enum Decision {
        /**
         * 继续重试
         */
        RETRY,
        /**
         * 最终失败
         */
        FAIL_TERMINAL
    }

    @Getter
    @RequiredArgsConstructor
    public static class Plan {
        private final Decision decision;
        private final long delayMillis;
    }
}
