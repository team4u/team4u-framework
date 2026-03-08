package com.team4u.framework.retry.backend;

import lombok.Builder;
import lombok.Data;

/**
 * 重试任务关闭请求，用于通知后端存储停止对该任务的重试
 */
@Data
@Builder
public class RetryCloseRequest {

    /**
     * 结束结果
     */
    private RetryCloseOutcome outcome;

    /**
     * 终止原因，通常在 outcome 为 FAILED 时提供
     */
    private RetryCloseReason reason;

    /**
     * 错误详细信息
     */
    private String errorMessage;

    /**
     * 创建一个标记为成功的关闭请求
     *
     * @return 成功的重试关闭请求
     */
    public static RetryCloseRequest succeeded() {
        return RetryCloseRequest.builder()
                .outcome(RetryCloseOutcome.SUCCEEDED)
                .build();
    }

    /**
     * 创建一个标记为失败的关闭请求
     *
     * @param reason       失败原因
     * @param errorMessage 错误消息
     * @return 失败的重试关闭请求
     */
    public static RetryCloseRequest failed(RetryCloseReason reason, String errorMessage) {
        return RetryCloseRequest.builder()
                .outcome(RetryCloseOutcome.FAILED)
                .reason(reason)
                .errorMessage(errorMessage)
                .build();
    }
}
