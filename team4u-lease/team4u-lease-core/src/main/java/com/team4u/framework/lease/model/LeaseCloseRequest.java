package com.team4u.framework.lease.model;

import com.team4u.framework.lease.enums.LeaseTaskFailureReason;
import com.team4u.framework.lease.enums.LeaseTaskOutcome;
import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * 关闭租约任务请求，用于标记任务的最终执行状态。
 */
@Data
@Builder
public class LeaseCloseRequest {

    /**
     * 关闭结果。
     */
    private LeaseTaskOutcome outcome;

    /**
     * 失败原因，仅 outcome=FAILED 时使用。
     */
    private LeaseTaskFailureReason failureReason;

    /**
     * 展示友好的错误摘要。
     */
    private String errorMessage;

    /**
     * 附加属性快照。
     */
    private Map<String, String> attributes;

    public static LeaseCloseRequest succeeded() {
        return LeaseCloseRequest.builder()
                .outcome(LeaseTaskOutcome.SUCCEEDED)
                .build();
    }

    public static LeaseCloseRequest cancelled(String errorMessage) {
        return LeaseCloseRequest.builder()
                .outcome(LeaseTaskOutcome.CANCELLED)
                .errorMessage(errorMessage)
                .build();
    }

    public static LeaseCloseRequest failed(LeaseTaskFailureReason failureReason, String errorMessage) {
        return LeaseCloseRequest.builder()
                .outcome(LeaseTaskOutcome.FAILED)
                .failureReason(failureReason)
                .errorMessage(errorMessage)
                .build();
    }
}
