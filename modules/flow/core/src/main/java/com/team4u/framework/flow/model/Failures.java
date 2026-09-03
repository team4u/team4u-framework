package com.team4u.framework.flow.model;

import java.util.Objects;

/**
 * 流程失败诊断辅助工具类。
 *
 * @author jay.wu
 */
public final class Failures {

    private Failures() {
    }

    /**
     * 将异常转换为不可变的失败诊断信息。
     * 若异常为 {@link FlowExecutionException}，则保留其自定义结构化错误码；
     * 否则使用指定的默认错误码。
     *
     * @param error       异常对象，不能为 null
     * @param defaultCode 默认错误码，不能为 null 或空白
     * @return 失败诊断信息
     */
    public static Failure from(Throwable error, String defaultCode) {
        Objects.requireNonNull(error, "error must not be null");
        if (error instanceof FlowExecutionException) {
            FlowExecutionException fee = (FlowExecutionException) error;
            return Failure.of(fee.code(), fee.getMessage());
        }
        String message = error.getMessage();
        String desc = error.getClass().getName() + (message == null || message.trim().isEmpty() ? "" : ": " + message);
        return Failure.of(defaultCode, desc);
    }
}
