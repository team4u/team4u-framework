package com.team4u.framework.flow;

/**
 * Policy 回调可见的上下文：元数据、当前重试 attempt 与取消信号。
 */
public interface PolicyContext {
    Metadata metadata();

    int attempt();

    Cancellation.Signal cancellation();
}
