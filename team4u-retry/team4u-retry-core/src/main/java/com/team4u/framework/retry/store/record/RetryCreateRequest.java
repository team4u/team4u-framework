package com.team4u.framework.retry.store.record;

import com.team4u.framework.retry.domain.store.RetryRequest;
import com.team4u.framework.retry.domain.store.RetryState;
import lombok.Builder;
import lombok.Data;

/**
 * 创建持久化重试记录（RetryRecord）时的核心载体，封装了原始请求及初始状态信息。
 */
@Data
@Builder
public class RetryCreateRequest {

    /**
     * 重试任务的原始定义，包含业务参数、幂等键及策略配置
     */
    private RetryRequest request;

    /**
     * 重试任务的初始状态快照
     */
    private RetryState initialState;
}
