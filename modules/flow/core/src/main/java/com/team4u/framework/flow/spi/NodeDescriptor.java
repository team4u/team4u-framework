package com.team4u.framework.flow.spi;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.util.Objects;
import java.util.Optional;
import com.team4u.framework.flow.api.FlowObserver;

/**
 * 编译后运行时节点的静态描述符（携带拓扑路径、标签、节点类型与组件绑定信息）。
 *
 * <p>用于在运行时事件通知（{@link FlowObserver.Event}）与链路追踪中标识当前正在执行的物理节点：
 * <ul>
 *   <li>{@code path}：节点在树中的绝对拓扑路径（例如 {@code $.0.1}）；</li>
 *   <li>{@code label}：用户显式定义的直观节点名称；</li>
 *   <li>{@code kind}：节点类型分类；</li>
 *   <li>{@code contractClass} / {@code implementationClass} / {@code qualifier}：执行步骤或策略所绑定的类与 Spring 限定符信息。</li>
 * </ul>
 * </p>
 *
 * @author jay.wu
 */
@Getter
@Accessors(fluent = true)
@EqualsAndHashCode
@ToString
public final class NodeDescriptor {
    /** 节点的绝对树路径。 */
    private final String path;
    /** 可选的节点可读标签。 */
    private final Optional<String> label;
    /** 节点类型分类。 */
    private final Kind kind;
    /** 绑定的契约接口 Class（若有）。 */
    private final Optional<Class<?>> contractClass;
    /** 绑定的具体实现类 Class（若有）。 */
    private final Optional<Class<?>> implementationClass;
    /** 绑定的 Spring/Bean 限定符（若有）。 */
    private final Optional<String> qualifier;

    /**
     * 构造运行时节点描述符。
     *
     * @param path                节点树路径，不能为 null 或空白
     * @param label               可选标签，不能为 null
     * @param kind                节点种类，不能为 null
     * @param contractClass       契约 Class 容器，不能为 null
     * @param implementationClass 实现 Class 容器，不能为 null
     * @param qualifier           限定符容器，不能为 null
     * @throws NullPointerException     当任何入参为 null 时抛出
     * @throws IllegalArgumentException 当字符串参数为空白时抛出
     */
    public NodeDescriptor(String path, Optional<String> label, Kind kind,
                          Optional<Class<?>> contractClass,
                          Optional<Class<?>> implementationClass,
                          Optional<String> qualifier) {
        this.path = text(path, "path");
        this.label = checked(label, "label");
        this.kind = Objects.requireNonNull(kind, "kind must not be null");
        this.contractClass = Objects.requireNonNull(contractClass, "contractClass must not be null");
        this.implementationClass = Objects.requireNonNull(implementationClass, "implementationClass must not be null");
        this.qualifier = checked(qualifier, "qualifier");
    }

    /**
     * 节点类型分类枚举。
     */
    public enum Kind {
        /** 操作调用节点。 */
        INVOKE,
        /** 顺序流水线/作用域节点。 */
        SEQUENCE,
        /** 条件路由节点。 */
        ROUTE,
        /** 降级恢复节点。 */
        FALLBACK,
        /** 结构化并发并行节点。 */
        PARALLEL,
        /** 异步挂起等待节点。 */
        AWAIT,
        /** 环绕治理控制节点。 */
        CONTROL,
        /** 常量/恒等终态节点。 */
        COMPLETE
    }

    /** 构造无绑定的结构节点描述符（Invoke 之外的节点）。 */
    public static NodeDescriptor structural(String path, String label, Kind kind) {
        return new NodeDescriptor(path, Optional.ofNullable(label), kind,
                Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static Optional<String> checked(Optional<String> value, String name) {
        return Objects.requireNonNull(value, name + " must not be null")
                .map(text -> text(text, name));
    }

    private static String text(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.trim().isEmpty()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}

