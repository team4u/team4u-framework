package com.team4u.framework.lease;

/**
 * 运维/管理接口。
 */
public interface LeaseAdminService {

    LeaseAdminResult reschedule(String taskId, long delayMillis);

    LeaseAdminResult cancel(String taskId);

    LeaseAdminResult requeueDead(String taskId, long delayMillis);
}
