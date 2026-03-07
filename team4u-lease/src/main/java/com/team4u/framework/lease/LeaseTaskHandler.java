package com.team4u.framework.lease;

/**
 * 租约任务处理器接口。
 */
public interface LeaseTaskHandler {

    void handle(LeaseExecutionContext context) throws Exception;
}
