package com.team4u.framework.flow.definition.binding;

import com.team4u.framework.flow.Flow;
import com.team4u.framework.flow.Local;
import com.team4u.framework.flow.LocalExecutable;
import com.team4u.framework.flow.definition.model.FlowDefinitionMetadata;
import com.team4u.framework.flow.definition.model.SourceSpan;
import com.team4u.framework.flow.definition.type.TypeRef;
import com.team4u.framework.flow.desc.FlowDescription;
import com.team4u.framework.flow.spi.OperationResolver;
import lombok.Builder;
import lombok.Getter;
import lombok.experimental.Accessors;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 外部流程定义与 Flow 核心绑定产物（Bound Flow）。
 *
 * <p>包含通过类型校验后构造的强类型 {@link Flow} 实例、Compiler Path 到源码 {@link SourceSpan} 的映射表，
 * 以及流程元数据。</p>
 *
 * @author jay.wu
 */
@Getter
@Accessors(fluent = true)
public final class BoundFlow {

    private final Flow<?, ?> flow;
    private final Map<String, SourceSpan> sourceMap;
    private final FlowDefinitionMetadata metadata;
    private final TypeRef inputType;
    private final TypeRef outputType;
    private final OperationResolver resolver;

    @Builder(toBuilder = true)
    public BoundFlow(
            Flow<?, ?> flow,
            Map<String, SourceSpan> sourceMap,
            FlowDefinitionMetadata metadata,
            TypeRef inputType,
            TypeRef outputType,
            OperationResolver resolver) {
        this.flow = Objects.requireNonNull(flow, "flow must not be null");
        this.sourceMap = sourceMap != null
                ? Collections.unmodifiableMap(new LinkedHashMap<String, SourceSpan>(sourceMap))
                : Collections.<String, SourceSpan>emptyMap();
        this.metadata = metadata;
        this.inputType = inputType != null ? inputType : TypeRef.ANY;
        this.outputType = outputType != null ? outputType : TypeRef.ANY;
        this.resolver = resolver;
    }

    /**
     * 编译为本地极速执行器（优先使用绑定期解析器，若无则使用全局默认解析器）。
     *
     * @param <I> 输入类型
     * @param <O> 输出类型
     * @return 本地执行器
     */
    @SuppressWarnings("unchecked")
    public <I, O> LocalExecutable<I, O> compileLocal() {
        return compileLocal(this.resolver != null ? this.resolver : OperationResolver.defaultResolver());
    }

    /**
     * 以指定的组件解析器编译为本地极速执行器。
     *
     * @param resolver 组件解析器
     * @param <I>      输入类型
     * @param <O>      输出类型
     * @return 本地执行器
     */
    @SuppressWarnings("unchecked")
    public <I, O> LocalExecutable<I, O> compileLocal(OperationResolver resolver) {
        return Local.compile((Flow<I, O>) flow, resolver);
    }

    /**
     * 导出结构化描述模型。
     *
     * @return 描述模型
     */
    public FlowDescription describe() {
        String flowId = metadata != null ? metadata.id() : null;
        return flow.describe(flowId);
    }
}
