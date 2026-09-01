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
 * 策略键提取函数描述符（Key Projection Descriptor，I -> K）。
 *
 * @author jay.wu
 */
@Getter
@Accessors(fluent = true)
@EqualsAndHashCode
@ToString
public final class KeyProjectionDescriptor {

    private final String id;
    private final TypeRef inputType;
    private final TypeRef keyType;
    private final Function<Object, Object> function;

    @Builder(toBuilder = true)
    @SuppressWarnings("unchecked")
    public KeyProjectionDescriptor(
            String id,
            TypeRef inputType,
            TypeRef keyType,
            Function<?, ?> function) {
        this.id = Objects.requireNonNull(id, "key projection id must not be null");
        this.inputType = inputType != null ? inputType : TypeRef.ANY;
        this.keyType = keyType != null ? keyType : TypeRef.ANY;
        this.function = (Function<Object, Object>) Objects.requireNonNull(function, "function must not be null");
    }
}
