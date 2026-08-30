package com.team4u.framework.retry.managed;

import com.team4u.framework.retry.managed.model.RetryStatus;
import lombok.Data;

import java.time.Instant;

/**
 * 托管提交结果模型。
 * 由于兼容 Java 8，暂不使用 sealed interface。
 * <p>
 * 分为以下几种可能的结果：
 * <ul>
 * <li>{@link Completed}: 前台执行成功完成</li>
 * <li>{@link Accepted}: 新任务已经被可靠托管接受，并被移交至后台调度</li>
 * <li>{@link Existing}: 命中了已存在的幂等任务，返回当前持久化快照</li>
 * <li>{@link Rejected}: 任务因运行期环境原因未能被 durable 接受</li>
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
     * 判断是否命中了已有幂等任务
     */
    default boolean isExisting() {
        return this instanceof Existing;
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

    /**
     * 已完成：表示任务在前台执行过程中已成功返回结果。
     */
    @Data
    class Completed<T> implements ManagedSubmitResult<T> {
        /**
         * 业务逻辑返回的实际结果值
         */
        private final T value;
    }

    /**
     * 已受理：表示新任务已被持久化并移交给后台调度。
     */
    @Data
    class Accepted<T> implements ManagedSubmitResult<T> {
        /**
         * 任务在重试系统内的唯一标识 taskId
         */
        private final String taskId;
        /**
         * 任务当前生命周期所处的状态
         */
        private final RetryStatus status;
        /**
         * 预期的下一次执行（后台重试尝试）时间点
         */
        private final Instant nextAttemptAt;
    }

    /**
     * 已存在：表示本次提交命中了幂等任务，返回其当前持久化状态。
     */
    @Data
    class Existing<T> implements ManagedSubmitResult<T> {
        /**
         * 任务在重试系统内的唯一标识 taskId
         */
        private final String taskId;
        /**
         * 任务当前生命周期所处的状态
         */
        private final RetryStatus status;
        /**
         * 若任务尚未终结，预期的下一次执行时间点
         */
        private final Instant nextAttemptAt;
    }

    /**
     * 执行失败：表示任务前台执行失败，且依据策略已确定不再重试。
     */
    @Data
    class Failed<T> implements ManagedSubmitResult<T> {
        /**
         * 导致失败的原始异常或错误对象
         */
        private final Throwable error;
    }

    /**
     * 被拒绝：表示任务因运行期环境原因未能被重试引擎 durable 接受。
     */
    @Data
    class Rejected<T> implements ManagedSubmitResult<T> {
        /**
         * 拒绝接收该任务的具体原因描述
         */
        private final String reason;
    }
}
