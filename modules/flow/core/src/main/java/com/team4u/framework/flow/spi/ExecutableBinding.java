package com.team4u.framework.flow.spi;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.experimental.Accessors;

import java.util.Objects;
import java.util.Optional;
import com.team4u.framework.flow.api.Operation;
import com.team4u.framework.flow.api.PersistentPolicy;
import com.team4u.framework.flow.api.Policy;

/**
 * 编译解析后的强类型执行绑定描述符。
 *
 * <p>持有已被解析并实例化的目标组件（{@link Operation}、{@link Policy} 或 {@link PersistentPolicy}）、
 * 契约接口、真实实现类、Spring 限定符与绑定类型。</p>
 *
 * @author jay.wu
 */
@Getter
@Accessors(fluent = true)
@EqualsAndHashCode
public final class ExecutableBinding {

    /**
     * 组件绑定类型枚举。
     */
    public enum Kind {
        /** 原子操作。 */
        OPERATION,
        /** 内存无状态策略。 */
        POLICY,
        /** 持久化有状态策略。 */
        PERSISTENT_POLICY,
        /** 并行汇聚策略。 */
        JOIN
    }

    /** 已解析的目标组件实例。 */
    private final Object instance;
    /** 契约接口 Class。 */
    private final Class<?> contractClass;
    /** 实际实现类 Class。 */
    private final Class<?> implementationClass;
    /** 可选的 Spring/Bean 限定符。 */
    private final Optional<String> qualifier;
    /** 绑定种类。 */
    private final Kind kind;

    /**
     * 构造执行绑定描述符。
     *
     * @param instance            组件实例，不能为 null
     * @param contractClass       契约 Class，不能为 null
     * @param implementationClass 实现 Class，不能为 null
     * @param qualifier           限定符，可为 null
     * @param kind                绑定种类，不能为 null
     * @throws NullPointerException 当任何必要参数为 null 时抛出
     */
    public ExecutableBinding(Object instance, Class<?> contractClass,
                             Class<?> implementationClass, String qualifier, Kind kind) {
        this.instance = Objects.requireNonNull(instance, "instance must not be null");
        this.contractClass = Objects.requireNonNull(contractClass, "contractClass must not be null");
        this.implementationClass = Objects.requireNonNull(implementationClass, "implementationClass must not be null");
        this.qualifier = Optional.ofNullable(qualifier);
        this.kind = Objects.requireNonNull(kind, "kind must not be null");
    }

    @Override
    public String toString() {
        return "ExecutableBinding[contractClass=" + contractClass.getName()
                + ", implementationClass=" + implementationClass.getName()
                + ", qualifier=" + qualifier
                + ", kind=" + kind + "]";
    }
}

