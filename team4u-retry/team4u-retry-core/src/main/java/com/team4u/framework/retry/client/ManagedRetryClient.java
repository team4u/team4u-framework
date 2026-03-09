package com.team4u.framework.retry.client;

import com.team4u.framework.retry.domain.ManagedSubmitResult;
import com.team4u.framework.retry.domain.RetryTaskSpec;

/**
 * 支持持久化托管的重试客户端。
 */
public interface ManagedRetryClient {

    /**
     * 向托管平台提交任务
     *
     * @param spec 完整包含所有重试规格的任务定义对象
     * @param <T>  返回值泛型
     * @return 表示任务状态及流转终态的组合结果，由于托管特质，此调用不会直接通过抛异常来转移权柄，而是返回 Accepted 或 Failed
     */
    <T> ManagedSubmitResult<T> submit(RetryTaskSpec<T> spec);
}
