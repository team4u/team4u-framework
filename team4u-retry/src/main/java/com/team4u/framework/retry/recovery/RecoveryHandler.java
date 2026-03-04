package com.team4u.framework.retry.recovery;

import com.team4u.framework.policy.api.KeyedPolicy;

/**
 * 分布式恢复处理器
 * <p>
 * 用于宕机或降级后，从后端存储捞取任务并恢复执行。
 */
public interface RecoveryHandler extends KeyedPolicy<String> {

    /**
     * 执行恢复逻辑
     *
     * @param payload 存入后端的任务快照数据
     * @throws Exception 如果恢复过程中出现异常
     */
    void recover(String payload) throws Exception;
}
