package com.team4u.framework.retry;

/**
 * 重试任务快照构建器
 * <p>
 * 用于在任务进入持久化存储前，将当前执行上下文转换为快照字符串。
 */
@FunctionalInterface
public interface RetryPayloadBuilder {

    /**
     * 构建任务快照
     *
     * @param context 重试快照构建上下文
     * @return 序列化后的任务快照数据
     */
    String build(RetryPayloadContext context);
}
