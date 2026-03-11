package com.team4u.framework.retry.client;

/**
 * 表示业务执行已经成功返回，但 durable SUCCEEDED 状态写入失败。
 * <p>
 * 调用方可据此区分“业务失败”和“成功结果未能完成 durable 提交”这两类不同风险。
 */
public class DurableSuccessWriteException extends RuntimeException {

    private final String taskId;

    public DurableSuccessWriteException(String taskId, Throwable cause) {
        super("Business execution succeeded but durable success state write failed for taskId=" + taskId, cause);
        this.taskId = taskId;
    }

    public String getTaskId() {
        return taskId;
    }
}
