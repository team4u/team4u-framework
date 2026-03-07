package com.team4u.framework.lease;

/**
 * 任务生产接口。
 */
public interface LeaseProducer {

    String publish(LeasePublishRequest request);
}
