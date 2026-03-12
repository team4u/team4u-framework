package com.team4u.framework.lease.model;

import com.team4u.framework.lease.enums.LeaseTaskFailureReason;
import com.team4u.framework.lease.enums.LeaseTaskOutcome;
import lombok.Builder;
import lombok.Data;

import java.util.Collections;
import java.util.LinkedHashMap;
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
     * 关闭时同步更新的任务负载（可选）。
     */
    private String payload;

    /**
     * 附加属性快照。
     * 空 map 表示不修改现有属性。
     */
    private Map<String, String> attributes;

    private static LeaseCloseRequest validate(LeaseTaskOutcome outcome,
                                              LeaseTaskFailureReason failureReason,
                                              String errorMessage,
                                              String payload,
                                              Map<String, String> attributes) {
        if (outcome == null) {
            throw new IllegalArgumentException("request.outcome must not be null");
        }
        if (outcome == LeaseTaskOutcome.FAILED && failureReason == null) {
            throw new IllegalArgumentException("request.failureReason must not be null when outcome is FAILED");
        }
        if (outcome != LeaseTaskOutcome.FAILED && failureReason != null) {
            throw new IllegalArgumentException("request.failureReason must be null unless outcome is FAILED");
        }
        return LeaseCloseRequest.builder()
                .outcome(outcome)
                .failureReason(failureReason)
                .errorMessage(errorMessage)
                .payload(payload)
                .attributes(attributes)
                .build();
    }

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

    public LeaseCloseRequest normalizeForRuntime() {
        return validate(outcome, failureReason, errorMessage, payload, attributes);
    }

    public LeaseCloseRequest normalizeForAdmin() {
        LeaseTaskFailureReason normalizedReason = failureReason;
        if (outcome == LeaseTaskOutcome.FAILED && normalizedReason == null) {
            normalizedReason = LeaseTaskFailureReason.MANUAL_FAIL;
        }
        return validate(outcome, normalizedReason, errorMessage, payload, attributes);
    }

    public Map<String, String> getAttributes() {
        if (attributes == null) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<String, String>(attributes));
    }
}
