package com.team4u.framework.flow.definition.registry;

import com.team4u.framework.flow.api.Operation;
import com.team4u.framework.flow.definition.type.TypeRef;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.util.Objects;

/**
 * 业务步骤操作描述符（Operation Descriptor）。
 *
 * @author jay.wu
 */
@Getter
@Accessors(fluent = true)
@EqualsAndHashCode
@ToString
public final class OperationDescriptor {

    private final String id;
    private final Class<? extends Operation<?, ?>> contract;
    private final Operation<?, ?> instance;
    private final TypeRef inputType;
    private final TypeRef outputType;
    private final String qualifier;

    @Builder(toBuilder = true)
    @SuppressWarnings("unchecked")
    public OperationDescriptor(
            String id,
            Class<? extends Operation<?, ?>> contract,
            Operation<?, ?> instance,
            TypeRef inputType,
            TypeRef outputType,
            String qualifier) {
        this.id = Objects.requireNonNull(id, "operation id must not be null");
        this.contract = contract != null
                ? contract
                : (instance != null ? (Class<? extends Operation<?, ?>>) instance.getClass() : null);
        this.instance = instance;
        this.inputType = inputType != null ? inputType : TypeRef.ANY;
        this.outputType = outputType != null ? outputType : TypeRef.ANY;
        this.qualifier = qualifier;
    }
}
