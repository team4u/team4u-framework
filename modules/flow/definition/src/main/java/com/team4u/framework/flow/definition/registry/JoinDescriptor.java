package com.team4u.framework.flow.definition.registry;

import com.team4u.framework.flow.api.ContextualJoinStrategy;
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
    private final TypeRef contextInputType;
    private final JoinStrategy<?> strategy;
    private final Class<? extends JoinStrategy<?>> contract;
    private final String qualifier;

    @Builder(toBuilder = true)
    @SuppressWarnings("unchecked")
    public JoinDescriptor(
            String id,
            TypeRef outputType,
            TypeRef contextInputType,
            JoinStrategy<?> strategy,
            Class<? extends JoinStrategy<?>> contract,
            String qualifier) {
        this.id = Objects.requireNonNull(id, "join id must not be null");
        this.outputType = outputType != null ? outputType : TypeRef.ANY;
        this.contextInputType = contextInputType;
        this.strategy = strategy;
        this.contract = contract != null
                ? contract
                : (strategy != null ? (Class<? extends JoinStrategy<?>>) strategy.getClass() : null);
        this.qualifier = qualifier;
    }

    public boolean isContextual() {
        return contextInputType != null
                || strategy instanceof ContextualJoinStrategy
                || (contract != null && ContextualJoinStrategy.class.isAssignableFrom(contract));
    }
}
