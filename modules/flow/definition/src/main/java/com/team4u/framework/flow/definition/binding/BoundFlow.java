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
     * 编译为默认的通用本地执行器（输入与输出类型为 Object）。
     *
     * @return 本地执行器
     */
    public LocalExecutable<Object, Object> compileLocal() {
        return compileLocal(this.resolver != null ? this.resolver : OperationResolver.defaultResolver());
    }

    /**
     * 以指定的组件解析器编译为通用本地执行器。
     *
     * @param resolver 组件解析器
     * @return 本地执行器
     */
    @SuppressWarnings("unchecked")
    public LocalExecutable<Object, Object> compileLocal(OperationResolver resolver) {
        return Local.compile((Flow<Object, Object>) flow, resolver);
    }

    /**
     * 校验请求的输入与输出类型，并编译为强类型本地执行器。
     *
     * @param inputType  期望的输入类型 Class
     * @param outputType 期望的输出类型 Class
     * @param <I>        输入类型
     * @param <O>        输出类型
     * @return 强类型本地执行器
     * @throws IllegalArgumentException 当请求的类型与 Flow 定义的静态类型不兼容时抛出
     */
    public <I, O> LocalExecutable<I, O> compileLocal(Class<I> inputType, Class<O> outputType) {
        return compileLocal(inputType, outputType, this.resolver != null ? this.resolver : OperationResolver.defaultResolver());
    }

    /**
     * 以指定的组件解析器校验请求类型并编译为强类型本地执行器。
     *
     * @param inputType  期望的输入类型 Class
     * @param outputType 期望的输出类型 Class
     * @param resolver   组件解析器
     * @param <I>        输入类型
     * @param <O>        输出类型
     * @return 强类型本地执行器
     * @throws IllegalArgumentException 当请求的类型与 Flow 定义的静态类型不兼容时抛出
     */
    @SuppressWarnings("unchecked")
    public <I, O> LocalExecutable<I, O> compileLocal(
            Class<I> inputType,
            Class<O> outputType,
            OperationResolver resolver) {
        validateTypes(inputType, outputType);
        return Local.compile((Flow<I, O>) flow, resolver);
    }

    /**
     * 转换为强类型绑定的视图句柄。
     *
     * @param inputType  输入类型 Class
     * @param outputType 输出类型 Class
     * @param <I>        输入类型
     * @param <O>        输出类型
     * @return 强类型绑定句柄
     */
    public <I, O> TypedBoundFlow<I, O> as(Class<I> inputType, Class<O> outputType) {
        validateTypes(inputType, outputType);
        return new TypedBoundFlow<I, O>(this, inputType, outputType);
    }

    private void validateTypes(Class<?> requestedInput, Class<?> requestedOutput) {
        if (requestedInput != null && this.inputType != TypeRef.ANY) {
            TypeRef requestedRef = TypeRef.of(requestedInput);
            if (!this.inputType.isAssignableFrom(requestedRef)) {
                throw new IllegalArgumentException(
                        "Requested input type " + requestedInput.getName()
                                + " is incompatible with flow input type " + this.inputType.typeName());
            }
        }
        if (requestedOutput != null && this.outputType != TypeRef.ANY) {
            TypeRef requestedRef = TypeRef.of(requestedOutput);
            if (!requestedRef.isAssignableFrom(this.outputType)) {
                throw new IllegalArgumentException(
                        "Requested output type " + requestedOutput.getName()
                                + " is incompatible with flow output type " + this.outputType.typeName());
            }
        }
    }

    /**
     * 强类型绑定句柄（Typed Bound Flow）。
     *
     * @param <I> 输入类型
     * @param <O> 输出类型
     */
    @Getter
    @Accessors(fluent = true)
    public static final class TypedBoundFlow<I, O> {
        private final BoundFlow delegate;
        private final Class<I> inputType;
        private final Class<O> outputType;

        TypedBoundFlow(BoundFlow delegate, Class<I> inputType, Class<O> outputType) {
            this.delegate = delegate;
            this.inputType = inputType;
            this.outputType = outputType;
        }

        public LocalExecutable<I, O> compileLocal() {
            return delegate.compileLocal(inputType, outputType);
        }

        public LocalExecutable<I, O> compileLocal(OperationResolver resolver) {
            return delegate.compileLocal(inputType, outputType, resolver);
        }

        @SuppressWarnings("unchecked")
        public Flow<I, O> flow() {
            return (Flow<I, O>) delegate.flow();
        }

        public BoundFlow raw() {
            return delegate;
        }
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
