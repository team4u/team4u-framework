package com.team4u.framework.retry.domain;

import java.time.Instant;

/**
 * 托管提交结果模型。
 * 由于兼容 Java 8，暂不使用 sealed interface。
 * <p>
 * 分为以下几种可能的结果：
 * <ul>
 * <li>{@link Completed}: 前台执行成功完成</li>
 * <li>{@link Accepted}: 任务已经被可靠托管接受，可能进行了部分重试，但被放入后台调度</li>
 * <li>{@link Rejected}: 任务被拒绝（例如：配置错误、资源不足等）</li>
 * <li>{@link Failed}: 明确的终端失败，不再重试也不会被托管</li>
 * </ul>
 *
 * @param <T> 具体的结果类型
 */
public interface ManagedSubmitResult<T> {

    /**
     * 判断是否是已完成
     */
    default boolean isCompleted() {
        return this instanceof Completed;
    }

    /**
     * 判断是否已被托管接受
     */
    default boolean isAccepted() {
        return this instanceof Accepted;
    }

    /**
     * 判断是否失败
     */
    default boolean isFailed() {
        return this instanceof Failed;
    }

    /**
     * 判断是否被拒绝
     */
    default boolean isRejected() {
        return this instanceof Rejected;
    }

    class Completed<T> implements ManagedSubmitResult<T> {
        private final T value;

        public Completed(T value) {
            this.value = value;
        }

        public T getValue() {
            return value;
        }
    }

    class Accepted<T> implements ManagedSubmitResult<T> {
        private final String taskId;
        // 先简单用字符串表示状态（例如 "PREPARED", "SCHEDULED"）
        private final String state;
        private final Instant nextAttemptAt;

        public Accepted(String taskId, String state, Instant nextAttemptAt) {
            this.taskId = taskId;
            this.state = state;
            this.nextAttemptAt = nextAttemptAt;
        }

        public String getTaskId() {
            return taskId;
        }

        public String getState() {
            return state;
        }

        public Instant getNextAttemptAt() {
            return nextAttemptAt;
        }
    }

    class Failed<T> implements ManagedSubmitResult<T> {
        private final Throwable error;

        public Failed(Throwable error) {
            this.error = error;
        }

        public Throwable getError() {
            return error;
        }
    }

    class Rejected<T> implements ManagedSubmitResult<T> {
        private final String reason;

        public Rejected(String reason) {
            this.reason = reason;
        }

        public String getReason() {
            return reason;
        }
    }
}
