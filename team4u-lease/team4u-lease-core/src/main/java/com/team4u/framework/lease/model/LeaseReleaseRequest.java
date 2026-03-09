package com.team4u.framework.lease.model;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * 租约释放请求
 * <p>
 * 用于将当前持有的任务释放回调度系统，并指定其下次可见的时间。
 */
@Data
@Builder
public class LeaseReleaseRequest {

    /**
     * 下次可见的延迟毫秒数
     */
    private long delayMillis;

    /**
     * 附加属性快照（可选，用于更新任务运行时状态）
     */
    private Map<String, String> attributes;

    /**
     * 释放时同步更新的任务负载（可选）。
     */
    private String payload;

    /**
     * 释放时记录的错误摘要（可选）。
     */
    private String errorMessage;

    public static LeaseReleaseRequest of(long delayMillis) {
        return LeaseReleaseRequest.builder()
                .delayMillis(delayMillis)
                .build();
    }

    public static LeaseReleaseRequest of(long delayMillis, String errorMessage) {
        return LeaseReleaseRequest.builder()
                .delayMillis(delayMillis)
                .errorMessage(errorMessage)
                .build();
    }
}
