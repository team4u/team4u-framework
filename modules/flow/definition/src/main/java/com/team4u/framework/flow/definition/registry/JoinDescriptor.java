package com.team4u.framework.flow.definition.registry;

import com.team4u.framework.flow.api.JoinStrategy;
import com.team4u.framework.flow.definition.type.TypeRef;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.util.Objects;

/**
 * 并行汇聚策略描述符（Join Descriptor）。
 *
 * @author jay.wu
 */
@Getter
@Accessors(fluent = true)
@EqualsAndHashCode
@ToString
public final class JoinDescriptor {

    private final String id;
    private final TypeRef outputType;
    private final JoinStrategy<?> strategy;
    private final Class<? extends JoinStrategy<?>> contract;

    @Builder(toBuilder = true)
    @SuppressWarnings("unchecked")
    public JoinDescriptor(
            String id,
            TypeRef outputType,
            JoinStrategy<?> strategy,
            Class<? extends JoinStrategy<?>> contract) {
        this.id = Objects.requireNonNull(id, "join id must not be null");
        this.outputType = outputType != null ? outputType : TypeRef.ANY;
        this.strategy = strategy;
        this.contract = contract != null
                ? contract
                : (strategy != null ? (Class<? extends JoinStrategy<?>>) strategy.getClass() : null);
    }
}
