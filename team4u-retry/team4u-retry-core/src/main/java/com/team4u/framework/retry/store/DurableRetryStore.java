package com.team4u.framework.retry.store;

import com.team4u.framework.retry.store.record.CancelRecord;
import com.team4u.framework.retry.store.record.FailureRecord;
import com.team4u.framework.retry.store.record.RetryRecord;
import com.team4u.framework.retry.store.record.SuccessRecord;

/**
 * 重试任务持久化存储接口。
 * <p>
 * 该接口定义 durable retry 的最小持久化能力：记录初始 intent，并在任务进入终态时完成持久化收尾。
 * 前台重试阶段的中间 attempt 属于进程内行为，不作为所有后端都必须兑现的 durable contract。
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
}
