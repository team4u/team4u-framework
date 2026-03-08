package com.team4u.framework.lease.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 租约释放请求
 * <p>
 * 用于将当前持有的任务释放回调度系统，并指定其下次可见的时间。
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
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
     * 失败原因（可选，记录释放时的错误上下文）
     */
    private Throwable cause;

    public static LeaseReleaseRequest of(long delayMillis) {
        return LeaseReleaseRequest.builder()
                .delayMillis(delayMillis)
                .build();
    }

    public static LeaseReleaseRequest of(long delayMillis, Throwable cause) {
        return LeaseReleaseRequest.builder()
                .delayMillis(delayMillis)
                .cause(cause)
                .build();
    }
}
