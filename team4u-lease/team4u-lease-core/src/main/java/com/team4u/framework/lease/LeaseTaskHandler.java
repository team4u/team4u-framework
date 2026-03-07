package com.team4u.framework.lease;

/**
 * 租约任务处理器接口
 * <p>
 * 开发人员需实现该接口以定义具体的业务处理逻辑。
 */
public interface LeaseTaskHandler {

    /**
     * 处理任务的核心逻辑
     *
     * @param context 任务执行上下文，包含任务快照及运行时工具
     * @throws Exception 处理过程中抛出的任何异常都将被 {@link LeaseWorker} 捕获并触发相应的重试或失败逻辑
     */
    void handle(LeaseExecutionContext context) throws Exception;
}
