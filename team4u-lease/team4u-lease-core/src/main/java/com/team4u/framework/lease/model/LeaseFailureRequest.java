package com.team4u.framework.lease.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 租约失败请求
 * <p>
 * 用于将当前持有的任务标记为最终失败状态。
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LeaseFailureRequest {

    /**
     * 导致失败的异常原因
     */
    private Throwable cause;

    /**
     * 附加属性快照（可选，用于更新任务运行时状态）
     */
    private Map<String, String> attributes;

    public static LeaseFailureRequest of(Throwable cause) {
        return LeaseFailureRequest.builder()
                .cause(cause)
                .build();
    }
}
