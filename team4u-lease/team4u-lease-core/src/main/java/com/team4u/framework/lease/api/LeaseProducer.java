package com.team4u.framework.lease.api;

import com.team4u.framework.lease.model.LeasePublishRequest;
import com.team4u.framework.lease.model.LeasePublishResult;
import com.team4u.framework.lease.runtime.LeaseWorker;

/**
 * 任务生产接口
 * <p>
 * 提供向租约系统发布分布式任务的能力。下游可以通过 {@link LeaseWorker} 订阅相关任务并处理。
 */
public interface LeaseProducer {

    /**
     * 发布一个延迟或即时执行的任务
     *
     * @param request 任务发布请求详情
     * @return 发布成功后的任务 ID，可用于管理操作
     */
    String publish(LeasePublishRequest request);

    /**
     * 按业务键（Business Key）幂等发布任务。
     * <p>
     * 若系统中已存在具有相同业务键的任务，则不再重复创建，而是直接返回该现有任务的信息。
     *
     * @param request 任务发布请求详情，包含业务键
     * @return 发布结果，包含是否新创建的标识及任务详情快照
     */
    LeasePublishResult publishIfAbsent(LeasePublishRequest request);
}
