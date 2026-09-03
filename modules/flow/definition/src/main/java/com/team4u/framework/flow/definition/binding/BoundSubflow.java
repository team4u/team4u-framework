package com.team4u.framework.flow.definition.binding;

import com.team4u.framework.flow.Flow;
import com.team4u.framework.flow.definition.type.TypeRef;

import java.util.Objects;

/**
 * 绑定后的子流程数据模型（包含逻辑 Flow 与输出类型引用）。
 *
 * @author jay.wu
 */
public final class BoundSubflow {
    private final Flow<?, ?> flow;
    private final TypeRef outputType;

    public BoundSubflow(Flow<?, ?> flow, TypeRef outputType) {
        this.flow = Objects.requireNonNull(flow, "flow must not be null");
        this.outputType = outputType != null ? outputType : TypeRef.ANY;
    }

    public Flow<?, ?> flow() {
        return flow;
    }

    public TypeRef outputType() {
        return outputType;
    }
}
