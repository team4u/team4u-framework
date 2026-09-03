package com.team4u.framework.flow.definition.binding;

import com.team4u.framework.flow.Flow;
import com.team4u.framework.flow.definition.diagnostic.Diagnostic;
import com.team4u.framework.flow.definition.diagnostic.DiagnosticCodes;
import com.team4u.framework.flow.definition.diagnostic.FlowDiagnosticException;
import com.team4u.framework.flow.definition.model.*;
import com.team4u.framework.flow.definition.property.CompiledReader;
import com.team4u.framework.flow.definition.property.CompiledWriter;
import com.team4u.framework.flow.definition.registry.FlowDefinitionRegistry;
import com.team4u.framework.flow.definition.registry.MergerDescriptor;
import com.team4u.framework.flow.definition.registry.ProjectorDescriptor;
import com.team4u.framework.flow.definition.type.TypeRef;

import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * 流程规范绑定上下文接口。
 *
 * @author jay.wu
 */
public interface BindingContext {

    /**
     * 获取符号注册表。
     *
     * @return 符号注册表
     */
    FlowDefinitionRegistry registry();

    /**
     * 获取当前上下文输入类型。
     *
     * @return 当前输入类型引用
     */
    default TypeRef currentType() {
        return registry() != null && registry().initialInputType() != null
                ? registry().initialInputType()
                : TypeRef.ANY;
    }

    /**
     * 获取指定 FlowSpec 节点的输入类型（由静态类型检查推导）。
     *
     * @param spec 流程规范节点
     * @return 节点输入类型引用
     */
    default TypeRef inputTypeOf(FlowSpec spec) {
        return currentType();
    }

    /**
     * 获取指定 FlowSpec 节点的输出类型（由静态类型检查推导）。
     *
     * @param spec 流程规范节点
     * @return 节点输出类型引用
     */
    default TypeRef outputTypeOf(FlowSpec spec) {
        return TypeRef.ANY;
    }

    /**
     * 递归绑定子 Spec 节点。
     *
     * @param spec 子 Spec 节点
     * @return 绑定的 Flow 实例
     */
    Flow<?, ?> bindSpec(FlowSpec spec);

    /**
     * 对 Flow 施加治理策略（支持 Provider 与静态 Descriptor，包含 Key 投影与配置解析）。
     *
     * @param flow          目标 Flow
     * @param policyId      策略 ID
     * @param keyRef        可选 Key 投影符号
     * @param configuration 配置字典
     * @return 增强后的 Flow 实例
     */
    Flow<?, ?> applyPolicy(
            Flow<?, ?> flow,
            String policyId,
            SymbolRef keyRef,
            Map<String, Object> configuration);

    /**
     * 编译投影规范为输入提取函数（显式指定根输入类型）。
     *
     * @param projection 投影规范
     * @param rootType   根输入类型
     * @return 提取函数
     */
    /**
     * 已编译的投影描述符，封装提取函数与产出类型。
     */
    final class CompiledProjection {
        private final Function<Object, Object> projector;
        private final TypeRef resultType;

        public CompiledProjection(Function<Object, Object> projector, TypeRef resultType) {
            this.projector = Objects.requireNonNull(projector, "projector must not be null");
            this.resultType = resultType != null ? resultType : TypeRef.ANY;
        }

        public Function<Object, Object> projector() {
            return projector;
        }

        public TypeRef resultType() {
            return resultType;
        }
    }

    /**
     * 编译投影规范为投影描述符（含提取函数与产出类型）。
     *
     * @param projection 投影规范
     * @param rootType   根输入类型
     * @return 投影描述符
     */
    default CompiledProjection compileCompiledProjection(ProjectionSpec projection, TypeRef rootType) {
        if (projection == null) {
            TypeRef actualRootType = rootType != null ? rootType : currentType();
            return new CompiledProjection(Function.identity(), actualRootType != null ? actualRootType : TypeRef.ANY);
        }
        TypeRef actualRootType = rootType != null ? rootType : currentType();
        if (projection instanceof PropertyProjectionSpec) {
            PropertyPath path = ((PropertyProjectionSpec) projection).path();
            CompiledReader reader = registry().propertyAccessCompiler().compileReader(actualRootType, path);
            return new CompiledProjection(reader::read, reader.resultType());
        }
        if (projection instanceof SymbolRef || projection instanceof SymbolProjectionSpec) {
            SymbolRef symbol = projection instanceof SymbolRef
                    ? (SymbolRef) projection
                    : ((SymbolProjectionSpec) projection).symbol();
            ProjectorDescriptor desc = registry().projector(symbol.id());
            if (desc == null) {
                throw new FlowDiagnosticException(new Diagnostic(
                        DiagnosticCodes.UNKNOWN_PROJECTOR,
                        "Unknown projector: " + symbol.id(),
                        symbol.span()));
            }
            TypeRef outType = desc.outputType() != null ? desc.outputType() : TypeRef.ANY;
            return new CompiledProjection(desc.function(), outType);
        }
        throw new FlowDiagnosticException(new Diagnostic(
                DiagnosticCodes.UNSUPPORTED_PROJECTION_SPEC,
                "Unsupported projection specification: " + projection.getClass().getName(),
                projection.span()));
    }

    /**
     * 编译投影规范为输入提取函数（显式指定根输入类型）。
     *
     * @param projection 投影规范
     * @param rootType   根输入类型
     * @return 提取函数
     */
    default Function<Object, Object> compileProjector(ProjectionSpec projection, TypeRef rootType) {
        return compileCompiledProjection(projection, rootType).projector();
    }

    /**
     * 编译投影规范为输入提取函数。
     *
     * @param projection 投影规范
     * @return 提取函数
     */
    default Function<Object, Object> compileProjector(ProjectionSpec projection) {
        return compileProjector(projection, currentType());
    }

    /**
     * 编译合并规范为结果合并函数（显式指定根输入类型）。
     *
     * @param merge      合并规范
     * @param rootType   根输入类型
     * @param resultType 结果输出类型
     * @return 合并函数
     */
    default BiFunction<Object, Object, Object> compileMerger(MergeSpec merge, TypeRef rootType, TypeRef resultType) {
        if (merge == null) {
            return (state, res) -> res;
        }
        TypeRef actualRootType = rootType != null ? rootType : currentType();
        if (merge instanceof PropertyMergeSpec) {
            PropertyPath path = ((PropertyMergeSpec) merge).path();
            CompiledWriter writer = registry().propertyAccessCompiler().compileWriter(
                    actualRootType, path, resultType != null ? resultType : TypeRef.ANY);
            return writer::write;
        }
        if (merge instanceof SymbolRef || merge instanceof SymbolMergeSpec) {
            SymbolRef symbol = merge instanceof SymbolRef
                    ? (SymbolRef) merge
                    : ((SymbolMergeSpec) merge).symbol();
            MergerDescriptor desc = registry().merger(symbol.id());
            if (desc == null) {
                throw new FlowDiagnosticException(new Diagnostic(
                        DiagnosticCodes.UNKNOWN_MERGER,
                        "Unknown merger: " + symbol.id(),
                        symbol.span()));
            }
            return desc.function();
        }
        throw new FlowDiagnosticException(new Diagnostic(
                DiagnosticCodes.UNSUPPORTED_MERGE_SPEC,
                "Unsupported merge specification: " + merge.getClass().getName(),
                merge.span()));
    }

    /**
     * 编译合并规范为结果合并函数。
     *
     * @param merge      合并规范
     * @param resultType 结果输出类型
     * @return 合并函数
     */
    default BiFunction<Object, Object, Object> compileMerger(MergeSpec merge, TypeRef resultType) {
        return compileMerger(merge, currentType(), resultType);
    }


    /**
     * 获取组件解析器。
     *
     * @return 组件解析器
     */
    default com.team4u.framework.flow.spi.OperationResolver resolver() {
        return null;
    }

    /**
     * 获取指定 ID 的子流程定义。
     *
     * @param id 子流程 ID
     * @return 子流程定义（若未注册则返回 null）
     */
    default com.team4u.framework.flow.definition.model.FlowDefinition subflow(String id) {
        return registry() != null ? registry().subflow(id) : null;
    }

    /**
     * 绑定子流程定义（由当前绑定会话管理，复用类型检查与缓存，避免嵌套编译）。
     *
     * @param subflowDef 子流程定义
     * @param inputType  子流程输入类型
     * @return 绑定的子流程结果（包含 Flow 与输出类型）
     */
    BoundSubflow bindSubflow(com.team4u.framework.flow.definition.model.FlowDefinition subflowDef, TypeRef inputType);
}
