package com.team4u.framework.retry.domain;

import com.team4u.framework.retry.domain.store.RetryStatus;
import lombok.Data;

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
     * 已受理：表示任务已被持久化存储并接受托管，可能正在等待后台重试调度。
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
     * 被拒绝：表示任务由于配置不合规、资源受限等原因被重试引擎拒绝下单。
     */
    @Data
    class Rejected<T> implements ManagedSubmitResult<T> {
        /**
         * 拒绝接收该任务的具体原因描述
         */
        private final String reason;
    }
}
