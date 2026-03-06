package com.team4u.framework.retry.recovery;

import com.team4u.framework.policy.api.KeyedPolicy;

/**
 * 分布式恢复处理器
 * <p>
 * 当系统发生宕机或任务降级后，用于从后端存储加载任务快照并恢复执行。
 */
public interface RecoveryHandler extends KeyedPolicy<String> {

    /**
     * 执行恢复逻辑
     *
     * @param payload 存储于后端的任务快照数据
     * @throws Exception 恢复执行过程中的异常
     */
    void recover(String payload) throws Exception;
}
