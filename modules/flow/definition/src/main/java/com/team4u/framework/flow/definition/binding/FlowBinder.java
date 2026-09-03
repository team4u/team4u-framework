package com.team4u.framework.flow.definition.binding;

import com.team4u.framework.flow.Flow;
import com.team4u.framework.flow.api.PersistentPolicy;
import com.team4u.framework.flow.api.Policy;
import com.team4u.framework.flow.compiler.Compiler;
import com.team4u.framework.flow.definition.diagnostic.Diagnostic;
import com.team4u.framework.flow.definition.diagnostic.DiagnosticCodes;
import com.team4u.framework.flow.definition.diagnostic.FlowDiagnosticException;
import com.team4u.framework.flow.definition.model.FlowDefinition;
import com.team4u.framework.flow.definition.model.FlowSpec;
import com.team4u.framework.parser.SourceSpan;
import com.team4u.framework.flow.definition.model.SymbolRef;
import com.team4u.framework.flow.definition.registry.*;
import com.team4u.framework.flow.definition.type.TypeCheckResult;
import com.team4u.framework.flow.definition.type.TypeChecker;
import com.team4u.framework.flow.definition.type.TypeRef;
import com.team4u.framework.flow.model.FlowBuildException;
import com.team4u.framework.flow.spi.OperationResolver;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * 流程定义绑定器（Flow Binder）。
 *
 * <p>基于 {@link SpecBinderRegistry} 将外部数据模型 {@link FlowDefinition} 与符号注册表 {@link FlowDefinitionRegistry}
 * 进行类型检查并绑定为强类型 {@link Flow} 逻辑 AST，同时通过 {@link SourceMapBuilder} 建立 Compiler Path 到 DSL 源码 {@link SourceSpan} 的映射。</p>
 *
 * @author jay.wu
 */
public final class FlowBinder implements BindingContext {

    private final FlowDefinitionRegistry registry;
    private final OperationResolver resolver;
    private final SpecBinderRegistry binderRegistry;
    private final TypeRef initialInputType;

    public FlowBinder(
            FlowDefinitionRegistry registry,
            OperationResolver resolver,
            SpecBinderRegistry binderRegistry,
            TypeRef initialInputType) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.resolver = resolver != null
                ? resolver
                : (registry.fallbackResolver() != null ? registry.fallbackResolver() : OperationResolver.defaultResolver());
        this.binderRegistry = binderRegistry != null ? binderRegistry : SpecBinderRegistry.global();
        this.initialInputType = initialInputType != null ? initialInputType : registry.initialInputType();
    }

    public FlowBinder(FlowDefinitionRegistry registry, OperationResolver resolver, SpecBinderRegistry binderRegistry) {
        this(registry, resolver, binderRegistry, null);
    }

    public FlowBinder(FlowDefinitionRegistry registry, OperationResolver resolver) {
        this(registry, resolver, SpecBinderRegistry.global(), null);
    }

    public FlowBinder(FlowDefinitionRegistry registry) {
        this(registry, null, SpecBinderRegistry.global(), null);
    }

    /**
     * 静态便捷绑定方法。
     *
     * @param definition 流程定义
     * @param registry   符号注册表
     * @return 绑定后的 BoundFlow
     */
    public static BoundFlow bind(FlowDefinition definition, FlowDefinitionRegistry registry) {
        return new FlowBinder(registry).bind(definition);
    }

    /**
     * 带初始输入类型的静态便捷绑定方法。
     *
     * @param definition       流程定义
     * @param registry         符号注册表
     * @param initialInputType 初始输入类型
     * @return 绑定后的 BoundFlow
     */
    public static BoundFlow bind(
            FlowDefinition definition,
            FlowDefinitionRegistry registry,
            TypeRef initialInputType) {
        return new FlowBinder(registry, null, SpecBinderRegistry.global(), initialInputType).bind(definition);
    }

    /**
     * 带组件解析器的静态便捷绑定方法。
     *
     * @param definition 流程定义
     * @param registry   符号注册表
     * @param resolver   组件解析器
     * @return 绑定后的 BoundFlow
     */
    public static BoundFlow bind(
            FlowDefinition definition,
            FlowDefinitionRegistry registry,
            OperationResolver resolver) {
        return new FlowBinder(registry, resolver).bind(definition);
    }

    /**
     * 带组件解析器及初始输入类型的静态便捷绑定方法。
     *
     * @param definition       流程定义
     * @param registry         符号注册表
     * @param resolver         组件解析器
     * @param initialInputType 初始输入类型
     * @return 绑定后的 BoundFlow
     */
    public static BoundFlow bind(
            FlowDefinition definition,
            FlowDefinitionRegistry registry,
            OperationResolver resolver,
            TypeRef initialInputType) {
        return new FlowBinder(registry, resolver, SpecBinderRegistry.global(), initialInputType).bind(definition);
    }

    private TypeCheckResult currentTypeCheckResult;

    @Override
    public TypeRef currentType() {
        return initialInputType != null ? initialInputType : TypeRef.ANY;
    }

    @Override
    public TypeRef inputTypeOf(FlowSpec spec) {
        if (currentTypeCheckResult != null && spec != null) {
            TypeRef t = currentTypeCheckResult.specInputTypes().get(spec);
            if (t != null) {
                return t;
            }
        }
        return currentType();
    }

    @Override
    public TypeRef outputTypeOf(FlowSpec spec) {
        if (currentTypeCheckResult != null && spec != null) {
            TypeRef t = currentTypeCheckResult.specOutputTypes().get(spec);
            if (t != null) {
                return t;
            }
        }
        return TypeRef.ANY;
    }

    /**
     * 执行类型检查、AST 绑定与编译器拓扑校验。
     *
     * @param definition 流程定义
     * @return 绑定结果 BoundFlow
     * @throws FlowDiagnosticException 当类型检查不通过或编译器校验报错时抛出
     */
    @SuppressWarnings("unchecked")
    public BoundFlow bind(FlowDefinition definition) {
        Objects.requireNonNull(definition, "flow definition must not be null");

        // 1. 执行静态类型检查
        TypeCheckResult typeCheckResult = TypeChecker.check(definition, registry, initialInputType);
        if (!typeCheckResult.success()) {
            throw new FlowDiagnosticException(typeCheckResult.diagnostics());
        }

        // 2. 递归绑定 AST 并执行编译器拓扑校验
        Flow<Object, Object> flow;
        Map<String, SourceSpan> sourceMap = Collections.emptyMap();
        this.currentTypeCheckResult = typeCheckResult;
        try {
            flow = (Flow<Object, Object>) bindSpec(definition.root());
            sourceMap = SourceMapBuilder.build(flow.root(), definition.root());
            Compiler.compile(flow, resolver);
        } catch (FlowBuildException ex) {
            List<Diagnostic> diagnostics = new ArrayList<Diagnostic>();
            for (FlowBuildException.Problem problem : ex.problems()) {
                SourceSpan span = SourceMapBuilder.findSourceSpan(sourceMap, problem.path(), definition.span());
                diagnostics.add(new Diagnostic(problem.code(), problem.message(), span, problem.path()));
            }
            throw new FlowDiagnosticException(diagnostics);
        } finally {
            this.currentTypeCheckResult = null;
        }

        return BoundFlow.builder()
                .flow(flow)
                .sourceMap(sourceMap)
                .metadata(definition.metadata())
                .inputType(typeCheckResult.inputType())
                .outputType(typeCheckResult.outputType())
                .resolver(this.resolver)
                .build();
    }

    @Override
    public FlowDefinitionRegistry registry() {
        return registry;
    }

    @Override
    public OperationResolver resolver() {
        return resolver;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Flow<?, ?> bindSpec(FlowSpec spec) {
        if (spec == null) {
            return Flow.identity();
        }
        SpecBinder<FlowSpec> binder = (SpecBinder<FlowSpec>) binderRegistry.get(spec.getClass()).orElse(null);
        if (binder != null) {
            return binder.bind(spec, this);
        }
        throw new FlowDiagnosticException(
                DiagnosticCodes.UNSUPPORTED_SPEC,
                "No binder registered for " + spec.getClass().getName());
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Flow<?, ?> applyPolicy(
            Flow<?, ?> flow,
            String policyId,
            SymbolRef keyRef,
            Map<String, Object> configuration) {
        PolicyProvider provider = registry.policyProvider(policyId);
        Function<Object, Object> keyFn = Function.identity();
        if (keyRef != null) {
            KeyProjectionDescriptor keyDesc = registry.keyProjection(keyRef.id());
            if (keyDesc != null) {
                keyFn = keyDesc.function();
            } else {
                ProjectorDescriptor projDesc = registry.projector(keyRef.id());
                if (projDesc != null) {
                    keyFn = projDesc.function();
                }
            }
        }

        if (provider != null) {
            PolicyBinding binding = provider.create(configuration);
            Function<Object, Object> actualKeyFn = binding.keyProjection() != null
                    ? binding.keyProjection()
                    : keyFn;
            if (actualKeyFn == null) {
                actualKeyFn = Function.identity();
            }
            if (binding.persistent()) {
                if (binding.instance() != null) {
                    return flow.persistentPolicy((PersistentPolicy) binding.instance(), actualKeyFn);
                } else {
                    return flow.persistentPolicy((Class) binding.contract(), binding.qualifier(), actualKeyFn);
                }
            } else {
                if (binding.instance() != null) {
                    return flow.policy((Policy) binding.instance(), actualKeyFn);
                } else {
                    return flow.policy((Class) binding.contract(), binding.qualifier(), actualKeyFn);
                }
            }
        }

        PolicyDescriptor policyDesc = registry.policy(policyId);
        if (policyDesc == null) {
            throw new FlowDiagnosticException(
                    DiagnosticCodes.UNKNOWN_POLICY, "Policy not found: " + policyId);
        }

        if (policyDesc.persistent()) {
            if (policyDesc.instance() != null) {
                return flow.persistentPolicy((PersistentPolicy) policyDesc.instance(), keyFn);
            } else {
                return flow.persistentPolicy((Class) policyDesc.contract(), policyDesc.qualifier(), keyFn);
            }
        } else {
            if (policyDesc.instance() != null) {
                return flow.policy((Policy) policyDesc.instance(), keyFn);
            } else {
                return flow.policy((Class) policyDesc.contract(), policyDesc.qualifier(), keyFn);
            }
        }
    }
}
