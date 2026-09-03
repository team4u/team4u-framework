package com.team4u.framework.flow.definition.type;

import com.team4u.framework.parser.SourceSpan;

import com.team4u.framework.flow.definition.diagnostic.Diagnostic;
import com.team4u.framework.flow.definition.diagnostic.DiagnosticCodes;
import com.team4u.framework.flow.definition.model.*;
import com.team4u.framework.flow.definition.registry.FlowDefinitionRegistry;
import com.team4u.framework.flow.definition.registry.OperationDescriptor;
import com.team4u.framework.flow.definition.registry.ProjectorDescriptor;

import com.team4u.framework.flow.definition.validation.FlowCallGraphValidator;
import com.team4u.framework.flow.definition.validation.FlowDefinitionValidator;

import java.util.*;

/**
 * 流程定义静态类型检查器（Type Checker）。
 *
 * <p>基于 {@link SpecTypeCheckerRegistry} 策略分发，遍历 FlowSpec AST 校验节点间输入输出类型兼容性、
 * 符号存在性、修饰器约束及路由字面量可解析性（Class-level static type checking）。</p>
 *
 * @author jay.wu
 */
public final class TypeChecker {

    private final FlowDefinitionRegistry registry;
    private final SpecTypeCheckerRegistry checkerRegistry;

    public TypeChecker(FlowDefinitionRegistry registry, SpecTypeCheckerRegistry checkerRegistry) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.checkerRegistry = checkerRegistry != null ? checkerRegistry : SpecTypeCheckerRegistry.global();
    }

    public TypeChecker(FlowDefinitionRegistry registry) {
        this(registry, SpecTypeCheckerRegistry.global());
    }

    /**
     * 静态便捷检查方法。
     *
     * @param definition 流程定义
     * @param registry   符号注册表
     * @return 类型检查结果
     */
    public static TypeCheckResult check(FlowDefinition definition, FlowDefinitionRegistry registry) {
        return new TypeChecker(registry).check(definition);
    }

    /**
     * 带初始输入类型的静态检查方法。
     *
     * @param definition       流程定义
     * @param registry         符号注册表
     * @param initialInputType 初始输入类型
     * @return 类型检查结果
     */
    public static TypeCheckResult check(
            FlowDefinition definition,
            FlowDefinitionRegistry registry,
            TypeRef initialInputType) {
        return new TypeChecker(registry).check(definition, initialInputType, null);
    }

    /**
     * 带初始输入类型及递归调用访问链路的静态检查方法。
     *
     * @param definition       流程定义
     * @param registry         符号注册表
     * @param initialInputType 初始输入类型
     * @param visitingFlows    当前访问链路
     * @return 类型检查结果
     */
    public static TypeCheckResult check(
            FlowDefinition definition,
            FlowDefinitionRegistry registry,
            TypeRef initialInputType,
            Set<String> visitingFlows) {
        return new TypeChecker(registry).check(definition, initialInputType, visitingFlows);
    }

    /**
     * 执行静态类型检查与推导（自动推导初始输入类型）。
     *
     * @param definition 流程定义
     * @return 类型检查结果
     */
    public TypeCheckResult check(FlowDefinition definition) {
        return check(definition, (TypeRef) null);
    }

    /**
     * 执行静态类型检查与推导。
     *
     * @param definition       流程定义
     * @param initialInputType 初始输入类型（若为 null 则自动从首节点推导）
     * @return 类型检查结果
     */
    public TypeCheckResult check(FlowDefinition definition, TypeRef initialInputType) {
        return check(definition, initialInputType, null);
    }

    /**
     * 执行静态类型检查与推导（包含调用栈访问链路）。
     *
     * @param definition       流程定义
     * @param initialInputType 初始输入类型（若为 null 则自动从首节点推导）
     * @param visitingFlows    当前调用链路中的流程 ID 集合
     * @return 类型检查结果
     */
    public TypeCheckResult check(
            FlowDefinition definition,
            TypeRef initialInputType,
            Set<String> visitingFlows) {
        if (definition == null) {
            return TypeCheckResult.builder()
                    .success(false)
                    .diagnostics(Collections.singletonList(new Diagnostic(
                            DiagnosticCodes.INVALID_DEFINITION,
                            "flow definition must not be null",
                            SourceSpan.UNKNOWN)))
                    .inputType(TypeRef.ANY)
                    .outputType(TypeRef.ANY)
                    .build();
        }

        TypeCheckContextImpl context = new TypeCheckContextImpl(
                registry, checkerRegistry, definition.id(), visitingFlows);

        List<Diagnostic> structuralDiagnostics = FlowDefinitionValidator.validate(definition);
        for (Diagnostic d : structuralDiagnostics) {
            context.addDiagnostic(d);
        }

        boolean topLevel = visitingFlows == null || visitingFlows.isEmpty();
        if (topLevel) {
            List<Diagnostic> cycleDiagnostics = FlowCallGraphValidator.validate(definition, registry);
            for (Diagnostic d : cycleDiagnostics) {
                context.addDiagnostic(d);
            }

            if (!cycleDiagnostics.isEmpty()) {
                return TypeCheckResult.builder()
                        .success(false)
                        .diagnostics(context.diagnostics())
                        .inputType(TypeRef.ANY)
                        .outputType(TypeRef.ANY)
                        .build();
            }
        }

        if (definition.root() == null) {
            return TypeCheckResult.builder()
                    .success(false)
                    .diagnostics(context.diagnostics())
                    .inputType(TypeRef.ANY)
                    .outputType(TypeRef.ANY)
                    .build();
        }

        TypeRef inputType = initialInputType != null
                ? initialInputType
                : inferInitialInputType(definition.root(), new HashSet<String>(context.visitingFlows()));

        TypeRef outputType = context.checkSpec(definition.root(), inputType);

        boolean success = context.diagnostics().isEmpty();
        return TypeCheckResult.builder()
                .success(success)
                .diagnostics(context.diagnostics())
                .inputType(inputType)
                .outputType(outputType)
                .specInputTypes(context.specInputTypes())
                .specOutputTypes(context.specOutputTypes())
                .build();
    }

    private TypeRef inferInitialInputType(FlowSpec spec, Set<String> visitedFlows) {
        if (spec == null) {
            return TypeRef.ANY;
        }
        if (spec instanceof StepSpec) {
            StepSpec step = (StepSpec) spec;
            SymbolRef projectRef = step.project();
            if (projectRef != null) {
                ProjectorDescriptor projector = registry.projector(projectRef.id());
                if (projector != null && projector.inputType() != TypeRef.ANY) {
                    return projector.inputType();
                }
            }
            if (step.operation() != null && step.operation().id() != null) {
                OperationDescriptor op = registry.operation(step.operation().id());
                if (op != null && op.inputType() != TypeRef.ANY) {
                    return op.inputType();
                }
            }
        } else if (spec instanceof CallSpec) {
            CallSpec call = (CallSpec) spec;
            SymbolRef projectRef = call.project();
            if (projectRef != null) {
                ProjectorDescriptor projector = registry.projector(projectRef.id());
                if (projector != null && projector.inputType() != TypeRef.ANY) {
                    return projector.inputType();
                }
            }
            if (call.flow() != null && call.flow().id() != null) {
                String targetId = call.flow().id();
                if (visitedFlows.add(targetId)) {
                    try {
                        FlowDefinition subflow = registry.subflow(targetId);
                        if (subflow != null && subflow.root() != null) {
                            return inferInitialInputType(subflow.root(), visitedFlows);
                        }
                    } finally {
                        visitedFlows.remove(targetId);
                    }
                }
                OperationDescriptor op = registry.operation(targetId);
                if (op != null && op.inputType() != TypeRef.ANY) {
                    return op.inputType();
                }
            }
        } else if (spec instanceof SequenceSpec) {
            SequenceSpec seq = (SequenceSpec) spec;
            if (seq.elements() != null && !seq.elements().isEmpty()) {
                return inferInitialInputType(seq.elements().get(0), visitedFlows);
            }
        } else if (spec instanceof RouteSpec) {
            RouteSpec route = (RouteSpec) spec;
            if (route.selector() != null && route.selector().id() != null) {
                OperationDescriptor op = registry.operation(route.selector().id());
                if (op != null && op.inputType() != TypeRef.ANY) {
                    return op.inputType();
                }
            }
        } else if (spec instanceof RecoverSpec) {
            return inferInitialInputType(((RecoverSpec) spec).body(), visitedFlows);
        } else if (spec instanceof ControlSpec) {
            return inferInitialInputType(((ControlSpec) spec).body(), visitedFlows);
        } else if (spec instanceof FirstApplicableSpec) {
            FirstApplicableSpec fa = (FirstApplicableSpec) spec;
            TypeRef inferred = TypeRef.ANY;
            if (fa.branches() != null) {
                for (FlowSpec branch : fa.branches()) {
                    TypeRef branchIn = inferInitialInputType(branch, visitedFlows);
                    inferred = narrowInputType(inferred, branchIn);
                }
            }
            return inferred;
        } else if (spec instanceof ParallelSpec) {
            ParallelSpec parallel = (ParallelSpec) spec;
            TypeRef inferred = TypeRef.ANY;
            if (parallel.branches() != null) {
                for (BranchSpec branch : parallel.branches()) {
                    if (branch != null && branch.flow() != null) {
                        TypeRef branchIn = inferInitialInputType(branch.flow(), visitedFlows);
                        inferred = narrowInputType(inferred, branchIn);
                    }
                }
            }
            return inferred;
        }
        return TypeRef.ANY;
    }

    private TypeRef narrowInputType(TypeRef t1, TypeRef t2) {
        if (t1 == null || t1 == TypeRef.ANY) {
            return t2 != null ? t2 : TypeRef.ANY;
        }
        if (t2 == null || t2 == TypeRef.ANY) {
            return t1;
        }
        if (t1.equals(t2)) {
            return t1;
        }
        if (t1.isAssignableFrom(t2)) {
            return t2;
        }
        if (t2.isAssignableFrom(t1)) {
            return t1;
        }
        return t1;
    }

    private static final class TypeCheckContextImpl implements TypeCheckContext {
        private final FlowDefinitionRegistry registry;
        private final SpecTypeCheckerRegistry checkerRegistry;
        private final List<Diagnostic> diagnostics = new ArrayList<Diagnostic>();
        private final Map<FlowSpec, TypeRef> specInputTypes = new IdentityHashMap<FlowSpec, TypeRef>();
        private final Map<FlowSpec, TypeRef> specOutputTypes = new IdentityHashMap<FlowSpec, TypeRef>();
        private final Set<String> visitingFlows = new HashSet<String>();

        TypeCheckContextImpl(
                FlowDefinitionRegistry registry,
                SpecTypeCheckerRegistry checkerRegistry,
                String rootFlowId,
                Set<String> initialVisiting) {
            this.registry = registry;
            this.checkerRegistry = checkerRegistry;
            if (initialVisiting != null) {
                this.visitingFlows.addAll(initialVisiting);
            }
            if (rootFlowId != null) {
                this.visitingFlows.add(rootFlowId);
            }
        }

        @Override
        public FlowDefinitionRegistry registry() {
            return registry;
        }

        @Override
        public boolean isVisiting(String flowId) {
            return flowId != null && visitingFlows.contains(flowId);
        }

        @Override
        public void pushVisiting(String flowId) {
            if (flowId != null) {
                visitingFlows.add(flowId);
            }
        }

        @Override
        public void popVisiting(String flowId) {
            if (flowId != null) {
                visitingFlows.remove(flowId);
            }
        }

        @Override
        public Set<String> visitingFlows() {
            return Collections.unmodifiableSet(visitingFlows);
        }

        @Override
        @SuppressWarnings("unchecked")
        public TypeRef checkSpec(FlowSpec spec, TypeRef currentType) {
            if (spec == null) {
                return currentType;
            }
            specInputTypes.put(spec, currentType);
            SpecTypeChecker<FlowSpec> checker = (SpecTypeChecker<FlowSpec>) checkerRegistry.get(spec.getClass()).orElse(null);
            if (checker == null) {
                addDiagnostic(new Diagnostic(
                        DiagnosticCodes.UNSUPPORTED_SPEC,
                        "No type checker registered for " + spec.getClass().getName(),
                        spec.span()));
                specOutputTypes.put(spec, currentType);
                return currentType;
            }
            TypeRef resultType = checker.check(spec, currentType, this);
            specOutputTypes.put(spec, resultType);
            return resultType;
        }

        @Override
        public void addDiagnostic(Diagnostic diagnostic) {
            if (diagnostic != null) {
                diagnostics.add(diagnostic);
            }
        }

        @Override
        public List<Diagnostic> diagnostics() {
            return diagnostics;
        }

        @Override
        public Map<FlowSpec, TypeRef> specInputTypes() {
            return specInputTypes;
        }

        @Override
        public Map<FlowSpec, TypeRef> specOutputTypes() {
            return specOutputTypes;
        }
    }
}
