package com.team4u.framework.flow;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.util.Objects;
import java.util.Optional;

/**
 * 节点绑定的组件元数据静态只读描述符。
 *
 * <p>用于向外暴露步骤所依赖的契约类型、实现类型、Spring 限定符以及绑定类型（OPERATION/POLICY/PERSISTENT_POLICY）。</p>
 *
 * @author team4u
 */
@Getter
@Accessors(fluent = true)
@EqualsAndHashCode
@ToString
public final class BindingDescriptor {
    /** 声明的契约接口 Class（若有）。 */
    private final Optional<Class<?>> contractClass;
    /** 绑定的具体实现类 Class（若有）。 */
    private final Optional<Class<?>> implementationClass;
    /** Spring/Bean 限定符名称（若有）。 */
    private final Optional<String> qualifier;
    /** 绑定种类字符串（OPERATION / POLICY / PERSISTENT_POLICY）。 */
    private final String kind;

    /**
     * 构造绑定描述符。
     *
     * @param contractClass       契约接口 Class，可为 null
     * @param implementationClass 实现类 Class，可为 null
     * @param qualifier           限定符，可为 null
     * @param kind                绑定类型，不能为 null
     * @throws NullPointerException 当 {@code kind} 为 null 时抛出
     */
    public BindingDescriptor(Class<?> contractClass, Class<?> implementationClass,
                             String qualifier, String kind) {
        this.contractClass = Optional.ofNullable(contractClass);
        this.implementationClass = Optional.ofNullable(implementationClass);
        this.qualifier = Optional.ofNullable(qualifier);
        this.kind = Objects.requireNonNull(kind, "kind must not be null");
    }
}

