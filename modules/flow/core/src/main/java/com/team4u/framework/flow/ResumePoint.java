package com.team4u.framework.flow;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.util.Objects;

/**
 * 流程异步挂起点（Await Point）的类型化稳定标识符。
 *
 * <p>用于在流程定义中显式标记等待外部恢复的接触点（Hook）：
 * <ul>
 *   <li>{@code name}：挂起点名称，在单个 Flow 拓扑定义中必须全局唯一；</li>
 *   <li>{@code <R>}：恢复该挂起点时必须传入的信号数据类型。</li>
 * </ul>
 * </p>
 *
 * <p>在执行到 {@code Flow.await(point)} 时，流程会进入挂起态；外部通过执行器调用
 * {@code resume(suspension, point, signal)} 并传入相匹配的信号恢复后续执行。</p>
 *
 * @param <R> 恢复该挂起点时所需信号数据的泛型类型
 * @author jay.wu
 */
@Getter
@Accessors(fluent = true)
@EqualsAndHashCode
@ToString
public final class ResumePoint<R> {
    /** 挂起点的唯一名称标识。 */
    private final String name;

    /**
     * 内部私有构造器。
     *
     * @param name 挂起点名称，不能为 null 或空白字符串
     */
    private ResumePoint(String name) {
        this.name = text(name);
    }

    /**
     * 创建具有指定名称与信号泛型的类型化挂起点标识。
     *
     * @param name 挂起点名称，在单个 Flow 内必须唯一，不能为 null 或空白
     * @param <R>  恢复信号类型
     * @return 挂起点标识实例
     * @throws NullPointerException     当 {@code name} 为 null 时抛出
     * @throws IllegalArgumentException 当 {@code name} 为空白字符串时抛出
     */
    public static <R> ResumePoint<R> named(String name) {
        return new ResumePoint<R>(name);
    }

    private static String text(String value) {
        Objects.requireNonNull(value, "name must not be null");
        if (value.trim().isEmpty()) throw new IllegalArgumentException("name must not be blank");
        return value;
    }
}

