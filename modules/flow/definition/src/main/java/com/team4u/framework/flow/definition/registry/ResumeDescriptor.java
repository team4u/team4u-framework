package com.team4u.framework.flow.definition.registry;

import com.team4u.framework.flow.definition.type.TypeRef;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.util.Objects;

/**
 * 异步挂起点描述符（Resume Descriptor）。
 *
 * @author jay.wu
 */
@Getter
@Accessors(fluent = true)
@EqualsAndHashCode
@ToString
public final class ResumeDescriptor {

    private final String id;
    private final TypeRef signalType;

    @Builder(toBuilder = true)
    public ResumeDescriptor(String id, TypeRef signalType) {
        this.id = Objects.requireNonNull(id, "resume point id must not be null");
        this.signalType = signalType != null ? signalType : TypeRef.ANY;
    }
}
