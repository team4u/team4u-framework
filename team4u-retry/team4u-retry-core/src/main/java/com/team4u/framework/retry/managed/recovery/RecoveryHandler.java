package com.team4u.framework.retry.managed.recovery;

import com.team4u.framework.policy.api.KeyedPolicy;

/**
 * 重试恢复处理器
 * <p>
 * 只有具备独立恢复能力的任务才需要实现此接口。
 *
 * @author jay.wu
 */
public interface RecoveryHandler<P> extends KeyedPolicy<String> {

    /**
     * 该处理器的唯一标识 Key 即为 taskName
     *
     * @return 任务名称
     */
    String taskName();

    @Override
    default String key() {
        return taskName();
    }

    /**
     * 执行恢复逻辑
     *
     * @param payload 业务载荷对象
     * @param context 恢复执行上下文
     * @throws Exception 恢复失败时抛出异常，以便触发后续重试逻辑
     */
    void recover(P payload, RecoveryContext context) throws Exception;
}
