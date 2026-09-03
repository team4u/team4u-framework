package com.team4u.framework.flow.model;

import java.util.Objects;

/**
 * 携带结构化错误码的流程执行异常（如属性访问失败、校验失败等）。
 *
 * <p>抛出此类异常时，运行时将直接提取其 {@link #code()} 并生成对应的 Failed 结果，而不会被抹平为通用 OPERATION_EXCEPTION。</p>
 *
 * @author jay.wu
 */
public class FlowExecutionException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final String code;

    public FlowExecutionException(String code, String message) {
        this(code, message, null);
    }

    public FlowExecutionException(String code, String message, Throwable cause) {
        super(checkText(message, "message"), cause);
        this.code = checkText(code, "code");
    }

    private static String checkText(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return trimmed;
    }

    /**
     * 获取结构化错误诊断码。
     *
     * @return 错误诊断码
     */
    public String code() {
        return code;
    }
}
