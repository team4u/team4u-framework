package com.team4u.framework.flow.definition.type;

import com.team4u.framework.flow.model.Recovery;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.experimental.Accessors;

import java.util.Objects;

/**
 * 失败降级复合类型引用：Recovery<I>。
 *
 * @author jay.wu
 */
@Getter
@Accessors(fluent = true)
@EqualsAndHashCode
public final class RecoveryTypeRef implements TypeRef {
    private static final long serialVersionUID = 1L;

    private final TypeRef inputType;

    public RecoveryTypeRef(TypeRef inputType) {
        this.inputType = Objects.requireNonNull(inputType, "inputType must not be null");
    }

    @Override
    public Class<?> rawType() {
        return Recovery.class;
    }

    @Override
    public String typeName() {
        return "Recovery<" + inputType.typeName() + ">";
    }

    @Override
    public boolean isAssignableFrom(TypeRef targetType) {
        if (targetType == null) {
            return false;
        }
        if (targetType instanceof RecoveryTypeRef) {
            RecoveryTypeRef other = (RecoveryTypeRef) targetType;
            return this.inputType.isAssignableFrom(other.inputType);
        }
        return Recovery.class.isAssignableFrom(targetType.rawType());
    }

    @Override
    public String toString() {
        return "Recovery<" + inputType.toString() + ">";
    }
}
