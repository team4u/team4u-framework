package com.team4u.framework.log;

/**
 * 日志区间（Span）
 * <p>
 * 代表一段执行区间，支持自动计算耗时。
 */
public final class LogSpan {

    private final Loggers delegate;
    private final long startNanos;
    private boolean startLogged;
    private boolean finished;

    LogSpan(Loggers delegate, long startNanos) {
        this.delegate = delegate;
        this.startNanos = startNanos;
    }

    /**
     * 添加业务数据 K-V 对
     *
     * @param key   键
     * @param value 值
     * @return 当前实例
     */
    public LogSpan put(String key, Object value) {
        delegate.put(key, value);
        return this;
    }

    /**
     * 显式记录开始日志
     * <p>
     * 使用派生的日志实例输出状态为 "start" 的日志，不影响当前 Span 的最终状态。
     *
     * @return 当前实例
     */
    public LogSpan logStart() {
        if (startLogged) {
            throw new IllegalStateException("开始日志已记录，请勿重复操作");
        }
        Loggers startLogger = delegate.derive().status("start");
        if (startLogger.getEvent().getLevel() == null) {
            startLogger.atInfo();
        }
        startLogger.log();
        startLogged = true;
        return this;
    }

    /**
     * 标记为成功状态
     *
     * @return 当前实例
     */
    public LogSpan success() {
        fillDurationIfAbsent();
        delegate.success();
        return this;
    }

    /**
     * 标记为失败状态并绑定异常
     *
     * @param e 异常对象
     * @return 当前实例
     */
    public LogSpan failed(Throwable e) {
        fillDurationIfAbsent();
        delegate.failed(e);
        return this;
    }

    /**
     * 设置状态
     *
     * @param status 状态字符串
     * @return 当前实例
     */
    public LogSpan status(String status) {
        fillDurationIfAbsent();
        delegate.status(status);
        return this;
    }

    /**
     * 提交最终日志
     * <p>
     * 记录此区间的结束，并计算耗时。
     */
    public void log() {
        if (finished) {
            throw new IllegalStateException("日志 Span 已结束，请勿重复记录结果");
        }
        finished = true;
        delegate.log();
    }

    /**
     * 如果未显式设置耗时，则自动根据开始时间填充
     */
    private void fillDurationIfAbsent() {
        if (delegate.getEvent().getDurationMs() < 0) {
            long costMs = (System.nanoTime() - startNanos) / 1_000_000;
            delegate.duration(costMs);
        }
    }
}
