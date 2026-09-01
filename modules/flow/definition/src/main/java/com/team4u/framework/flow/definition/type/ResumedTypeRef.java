package com.team4u.framework.flow.definition.type;

import com.team4u.framework.flow.model.Resumed;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.experimental.Accessors;

import java.util.Objects;

/**
 * 挂起恢复复合类型引用：Resumed<V, S>。
 *
 * @author jay.wu
 */
@Getter
@Accessors(fluent = true)
@EqualsAndHashCode
public final class ResumedTypeRef implements TypeRef {
    private static final long serialVersionUID = 1L;

    private final TypeRef valueType;
    private final TypeRef signalType;

    public ResumedTypeRef(TypeRef valueType, TypeRef signalType) {
        this.valueType = Objects.requireNonNull(valueType, "valueType must not be null");
        this.signalType = Objects.requireNonNull(signalType, "signalType must not be null");
    }

    @Override
    public Class<?> rawType() {
        return Resumed.class;
    }

    @Override
    public String typeName() {
        return "Resumed<" + valueType.typeName() + ", " + signalType.typeName() + ">";
    }

    @Override
    public boolean isAssignableFrom(TypeRef targetType) {
        if (targetType == null) {
            return false;
        }
        if (targetType instanceof ResumedTypeRef) {
            ResumedTypeRef other = (ResumedTypeRef) targetType;
            return this.valueType.isAssignableFrom(other.valueType)
                    && this.signalType.isAssignableFrom(other.signalType);
        }
        return Resumed.class.isAssignableFrom(targetType.rawType());
    }

    @Override
    public String toString() {
        return "Resumed<" + valueType.toString() + ", " + signalType.toString() + ">";
    }
}
