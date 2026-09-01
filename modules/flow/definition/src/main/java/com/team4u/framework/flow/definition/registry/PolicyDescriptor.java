package com.team4u.framework.flow.definition.registry;

import com.team4u.framework.flow.api.PersistentPolicy;
import com.team4u.framework.flow.definition.type.TypeRef;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.util.Objects;

/**
 * 治理策略描述符（Policy Descriptor）。
 *
 * @author jay.wu
 */
@Getter
@Accessors(fluent = true)
@EqualsAndHashCode
@ToString
public final class PolicyDescriptor {

    private final String id;
    private final Class<?> contract;
    private final Object instance;
    private final TypeRef keyType;
    private final String qualifier;
    private final boolean persistent;

    @Builder(toBuilder = true)
    public PolicyDescriptor(
            String id,
            Class<?> contract,
            Object instance,
            TypeRef keyType,
            String qualifier,
            Boolean persistent) {
        this.id = Objects.requireNonNull(id, "policy id must not be null");
        this.contract = contract != null
                ? contract
                : (instance != null ? instance.getClass() : null);
        this.instance = instance;
        this.keyType = keyType != null ? keyType : TypeRef.ANY;
        this.qualifier = qualifier;
        this.persistent = persistent != null
                ? persistent
                : (instance instanceof PersistentPolicy
                || (contract != null && PersistentPolicy.class.isAssignableFrom(contract)));
    }
}
