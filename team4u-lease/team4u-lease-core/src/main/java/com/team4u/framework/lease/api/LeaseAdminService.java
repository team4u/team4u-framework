package com.team4u.framework.lease.api;

import com.team4u.framework.lease.enums.LeaseAdminResult;

/**
 * 租约管理与运维服务接口
 * <p>
 * 提供对已有任务的手动干预能力，常用于管理后台或自动化补偿脚本。
 */
public interface LeaseAdminService {

    /**
     * 重排/调度任务
     * <p>
     * 更新任务的期望执行时间。
     *
     * @param taskId      全局唯一的任务 ID
     * @param delayMillis 期望的延迟执行毫秒数（从当前时间算起）
     * @return 操作结果状态
     */
    LeaseAdminResult reschedule(String taskId, long delayMillis);

    /**
     * 取消任务
     * <p>
     * 将任务标记为永久取消或从系统中移除。
     *
     * @param taskId 全局唯一的任务 ID
     * @return 操作结果状态
     */
    LeaseAdminResult cancel(String taskId);

    /**
     * 将死信任务重新放回就绪队列
     * <p>
     * 针对已进入失败终止状态的任务，修正其环境后重新触发执行。
     *
     * @param taskId      全局唯一的任务 ID
     * @param delayMillis 期望的延迟执行毫秒数
     * @return 操作结果状态
     */
    LeaseAdminResult requeueDead(String taskId, long delayMillis);
}
