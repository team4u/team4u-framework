package com.team4u.framework.flow;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 流程步骤执行失败（Failed）的不可变稳定诊断信息。
 *
 * <p>包含以下组成部分：
 * <ul>
 *   <li>{@code code}：稳定的错误故障码（非空、非空白），如 {@code TIMEOUT}、{@code OPERATION_EXCEPTION} 等；</li>
 *   <li>{@code message}：面向开发者或调用方的人类可读异常/故障说明；</li>
 *   <li>{@code details}：结构化扩展键值对字典（保持插入顺序且不可修改）。</li>
 * </ul>
 * </p>
 *
 * <p>该状态通常由操作异常抛出、执行超时、策略报错或显式返回触发，可被 {@link Retry} 重试拦截或 {@code recoverWith} 恢复降级。</p>
 *
 * @author jay.wu
 */
@Getter
@Accessors(fluent = true)
@EqualsAndHashCode
@ToString
public final class Failure {
    /** 稳定的系统/业务故障错误码。 */
    private final String code;
    /** 人类可读的失败说明描述。 */
    private final String message;
    /** 结构化键值对补充明细（不可变集合）。 */
    private final Map<String, String> details;

    /**
     * 构造完整的失败诊断对象。
     *
     * @param code    稳定的故障码，不能为 null 或空白字符串
     * @param message 失败说明，不能为 null 或空白字符串
     * @param details 结构化明细键值对字典，不能为 null
     * @throws NullPointerException     当任何入参为 null 时抛出
     * @throws IllegalArgumentException 当 {@code code} 或 {@code message} 为空白字符串时抛出
     */
    public Failure(String code, String message, Map<String, String> details) {
        this.code = text(code, "code");
        this.message = text(message, "message");
        Objects.requireNonNull(details, "details must not be null");
        this.details = Collections.unmodifiableMap(new LinkedHashMap<String, String>(details));
    }

    /**
     * 便捷静态工厂方法：创建无额外明细的失败诊断信息。
     *
     * @param code    稳定的故障码，不能为 null 或空白字符串
     * @param message 失败说明，不能为 null 或空白字符串
     * @return 初始化的 {@link Failure} 实例
     * @throws NullPointerException     当入参为 null 时抛出
     * @throws IllegalArgumentException 当入参为空白字符串时抛出
     */
    public static Failure of(String code, String message) {
        return new Failure(code, message, Collections.emptyMap());
    }

    private static String text(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.trim().isEmpty()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}

