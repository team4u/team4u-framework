package com.team4u.framework.retry.recovery;

import com.team4u.framework.policy.api.KeyedPolicy;
import com.team4u.framework.retry.backend.RetryTaskSnapshot;

/**
 * 重试恢复处理器
 * <p>
 * 只有具备独立恢复能力的任务才需要实现此接口。
 *
 * @author jay.wu
 */
public interface RecoveryHandler extends KeyedPolicy<String> {

    /**
     * 该处理器的唯一标识 Key
     *
     * @return 处理器 Key
     */
    @Override
    default String key() {
        return "";
    }

    /**
     * 执行恢复逻辑
     *
     * @param snapshot 任务快照，包含业务载荷及执行上下文
     * @throws Exception 恢复失败时抛出异常，以便触发后续重试逻辑
     */
    void recover(RetryTaskSnapshot snapshot) throws Exception;
}
