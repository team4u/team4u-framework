package com.team4u.framework.retry.client;

import com.team4u.framework.retry.domain.ManagedSubmitResult;
import com.team4u.framework.retry.domain.RetryTaskSpec;

/**
 * 持久化托管的重试客户端接口。
 * <p>
 * 该客户端支持将重试任务持久化到存储媒介（如数据库、Redis 等）中。
 * 即使应用服务宕机或重启，重试逻辑仍能保持状态，从而实现最终一致性和高可靠性。
 * 适用于关键业务流程、需要长时间跨度的重试或对数据完整性有严格要求的场景。
 */
public interface ManagedRetryClient {

    /**
     * 向重试平台提交任务。
     * <p>
     * 提交的任务会被持久化到持久层，并在适当的时候进行重试执行。
     * 如果重试策略配置了前台重试次数，任务将会在当前线程中尝试前几次执行。
     * 一旦前台重试耗尽，任务将交由协调中心进行后台异步调度。
     *
     * @param spec 完整的重试任务规格定义，包含业务逻辑、恢复策略及重试设置
     * @param <T>  业务执行结果的类型
     * @return 包含任务唯一标识、当前状态及其它上下文信息的提交结果
     */
    <T> ManagedSubmitResult<T> submit(RetryTaskSpec<T> spec);
}
