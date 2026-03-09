package com.team4u.framework.retry.store;

import com.team4u.framework.retry.store.record.*;

import java.time.Instant;
import java.util.Optional;

/**
 * 重试任务持久化存储接口。
 * <p>
 * 该接口定义了重试任务在状态机流转过程中的标准存储与更新操作。
 * 实现者需负责将任务的状态、执行记录及上下文负载持久化到外部介质（如数据库、消息队列或缓存），
 * 以确保重试任务在系统重启、节点故障或网络抖动后仍能根据既定策略继续执行。
 * </p>
 */
public interface DurableRetryStore {

    /**
     * 创建并持久化一个初始重试任务。
     * <p>
     * 在业务系统发起重试意图时调用，用于保存原始请求负载及初始元数据。
     * </p>
     *
     * @param initialRecord 包含重试请求、初始状态及配置的完整记录
     * @return 返回持久化系统生成的全局唯一任务 ID (taskId)，作为后续操作的唯一凭证
     */
    String create(RetryRecord initialRecord);

    /**
     * 标记重试任务进入“执行中”状态。
     * <p>
     * 在调度器获取任务并准备触发实际逻辑时调用，用于记录本次执行的节点、时间等上下文信息。
     * </p>
     *
     * @param taskId  全局唯一任务 ID
     * @param attempt 本次执行尝试的元数据（如 Worker ID、尝试时间等）
     */
    void markRunning(String taskId, AttemptRecord attempt);

    /**
     * 标记当前重试尝试失败，并安排下一次执行调度。
     * <p>
     * 当任务执行遇到可重试异常且尚未达到重试上限时调用。
     * 该方法需持久化本次失败的原因，并更新下一次预期的执行时间点。
     * </p>
     *
     * @param taskId    全局唯一任务 ID
     * @param attempt   当前执行尝试的元数据
     * @param nextRunAt 预期的下一次执行时间戳
     * @param failure   本次执行产生的错误详情记录
     */
    void scheduleNext(
            String taskId,
            AttemptRecord attempt,
            Instant nextRunAt,
            FailureRecord failure);

    /**
     * 标记重试任务已最终成功完成。
     * <p>
     * 当任务业务逻辑成功执行后调用，将任务状态迁移至终态，并清理或归档相关的中间状态。
     * </p>
     *
     * @param taskId  全局唯一任务 ID
     * @param success 成功完成时的上下文记录
     */
    void markSucceeded(String taskId, SuccessRecord success);

    /**
     * 标记重试任务为最终失败。
     * <p>
     * 当重试次数达到上限、遇到明确不可重试的异常或人工干预导致终止时调用。
     * 此后该任务将不再被自动调度执行。
     * </p>
     *
     * @param taskId  全局唯一任务 ID
     * @param failure 导致最终失败的错误原因记录
     */
    void markFailed(String taskId, FailureRecord failure);

    /**
     * 显式取消尚未完成的重试任务。
     * <p>
     * 响应外部管理指令或业务逻辑撤销请求，强制将任务迁移至已取消状态。
     * </p>
     *
     * @param taskId 全局唯一任务 ID
     * @param cancel 取消操作的相关上下文信息（如取消原因、操作人等）
     */
    void cancel(String taskId, CancelRecord cancel);

    /**
     * 根据任务 ID 查询当前重试任务的快照记录。
     *
     * @param taskId 全局唯一任务 ID
     * @return 包含任务当前状态、配置及历史记录的 Optional 封装
     */
    Optional<RetryRecord> get(String taskId);
}