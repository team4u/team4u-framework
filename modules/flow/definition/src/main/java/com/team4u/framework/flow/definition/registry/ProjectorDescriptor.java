package com.team4u.framework.flow.definition.registry;

import com.team4u.framework.flow.definition.type.TypeRef;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.util.Objects;
import java.util.function.Function;

/**
 * 投影函数描述符（Projector Descriptor，I -> P）。
 *
 * @author jay.wu
 */
@Getter
@Accessors(fluent = true)
@EqualsAndHashCode
@ToString
public final class ProjectorDescriptor {

    private final String id;
    private final TypeRef inputType;
    private final TypeRef outputType;
    private final Function<Object, Object> function;

    @Builder(toBuilder = true)
    @SuppressWarnings("unchecked")
    public ProjectorDescriptor(
            String id,
            TypeRef inputType,
            TypeRef outputType,
            Function<?, ?> function) {
        this.id = Objects.requireNonNull(id, "projector id must not be null");
        this.inputType = inputType != null ? inputType : TypeRef.ANY;
        this.outputType = outputType != null ? outputType : TypeRef.ANY;
        this.function = (Function<Object, Object>) Objects.requireNonNull(function, "function must not be null");
    }
}
