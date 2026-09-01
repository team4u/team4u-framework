package com.team4u.framework.flow.definition.registry;

import com.team4u.framework.flow.definition.type.TypeRef;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.util.Objects;
import java.util.function.BiFunction;

/**
 * 结果合并函数描述符（Merger Descriptor，(I, R) -> O）。
 *
 * @author jay.wu
 */
@Getter
@Accessors(fluent = true)
@EqualsAndHashCode
@ToString
public final class MergerDescriptor {

    private final String id;
    private final TypeRef stateType;
    private final TypeRef resultType;
    private final TypeRef outputType;
    private final BiFunction<Object, Object, Object> function;

    @Builder(toBuilder = true)
    @SuppressWarnings("unchecked")
    public MergerDescriptor(
            String id,
            TypeRef stateType,
            TypeRef resultType,
            TypeRef outputType,
            BiFunction<?, ?, ?> function) {
        this.id = Objects.requireNonNull(id, "merger id must not be null");
        this.stateType = stateType != null ? stateType : TypeRef.ANY;
        this.resultType = resultType != null ? resultType : TypeRef.ANY;
        this.outputType = outputType != null ? outputType : TypeRef.ANY;
        this.function = (BiFunction<Object, Object, Object>) Objects.requireNonNull(function, "function must not be null");
    }
}
