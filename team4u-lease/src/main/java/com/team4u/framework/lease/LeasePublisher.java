package com.team4u.framework.lease;

/**
 * 任务发布接口。
 */
public interface LeasePublisher {

    String publish(String taskType, String payload);

    String publish(String taskType, String payload, long delayMillis);

    void reschedule(String taskId, long delayMillis);

    void cancel(String taskId);
}
