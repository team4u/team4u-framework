package com.team4u.framework.lease.api;

import com.team4u.framework.lease.enums.LeaseAdminResult;
import com.team4u.framework.lease.model.LeaseCloseRequest;
import com.team4u.framework.lease.model.LeaseUpdateRequest;

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
     * 关闭任务。
     *
     * @param taskId  全局唯一的任务 ID
     * @param request 关闭请求
     * @return 操作结果状态
     */
    LeaseAdminResult close(String taskId, LeaseCloseRequest request);

    /**
     * 将失败任务重新放回就绪队列
     * <p>
     * 针对已关闭且结果为失败的任务，修正其环境后重新触发执行。
     *
     * @param taskId      全局唯一的任务 ID
     * @param delayMillis 期望的延迟执行毫秒数
     * @return 操作结果状态
     */
    LeaseAdminResult requeueFailed(String taskId, long delayMillis);

    /**
     * 更新任务内容
     *
     * @param request 任务更新请求
     * @return 操作结果状态
     */
    LeaseAdminResult update(LeaseUpdateRequest request);
}
