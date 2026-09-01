package com.team4u.framework.flow.definition.binding;

import com.team4u.framework.flow.Flow;
import com.team4u.framework.flow.api.Branch;
import com.team4u.framework.flow.api.JoinStrategy;
import com.team4u.framework.flow.api.Operation;
import com.team4u.framework.flow.api.PersistentPolicy;
import com.team4u.framework.flow.api.Policy;
import com.team4u.framework.flow.api.ResumePoint;
import com.team4u.framework.flow.compiler.Compiler;
import com.team4u.framework.flow.compiler.FlowPaths;
import com.team4u.framework.flow.compiler.Logical;
import com.team4u.framework.flow.definition.diagnostic.Diagnostic;
import com.team4u.framework.flow.definition.diagnostic.FlowDiagnosticException;
import com.team4u.framework.flow.definition.model.*;
import com.team4u.framework.flow.definition.registry.*;
import com.team4u.framework.flow.definition.type.TypeCheckResult;
import com.team4u.framework.flow.definition.type.TypeChecker;
import com.team4u.framework.flow.definition.type.TypeCodec;
import com.team4u.framework.flow.model.Failure;
import com.team4u.framework.flow.model.FlowBuildException;
import com.team4u.framework.flow.model.Reason;
import com.team4u.framework.flow.spi.OperationResolver;

import java.time.Duration;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * 流程定义绑定器（Flow Binder）。
 *
 * <p>将外部数据模型 {@link FlowDefinition} 与符号注册表 {@link FlowDefinitionRegistry} 进行类型检查并绑定为
 * 强类型 {@link Flow} 逻辑 AST，同时建立 Compiler Path 到 DSL 源码 {@link SourceSpan} 的映射（SourceMap）。</p>
 *
 * @author jay.wu
 */
public final class FlowBinder {

    private final FlowDefinitionRegistry registry;
    private final OperationResolver resolver;

    public FlowBinder(FlowDefinitionRegistry registry, OperationResolver resolver) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.resolver = resolver != null ? resolver : OperationResolver.rejecting();
    }

    public FlowBinder(FlowDefinitionRegistry registry) {
        this(registry, OperationResolver.rejecting());
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
     * 执行类型检查、AST 绑定与编译器拓扑校验。
     *
     * @param definition 流程定义
     * @return 绑定结果 BoundFlow
     * @throws FlowDiagnosticException 当类型检查不通过或编译器校验报错时抛出
     */
    public BoundFlow bind(FlowDefinition definition) {
        Objects.requireNonNull(definition, "flow definition must not be null");

        // 1. 执行静态类型检查
        TypeCheckResult typeCheckResult = TypeChecker.check(definition, registry);
        if (!typeCheckResult.success()) {
            throw new FlowDiagnosticException(typeCheckResult.diagnostics());
        }

        // 2. 递归绑定 AST 并执行编译器拓扑校验
        Map<String, SourceSpan> sourceMap = new LinkedHashMap<String, SourceSpan>();
        Flow<Object, Object> flow;
        try {
            flow = bindRoot(definition.root(), sourceMap);
            Compiler.compile(flow, resolver);
        } catch (FlowBuildException ex) {
            List<Diagnostic> diagnostics = new ArrayList<Diagnostic>();
            for (FlowBuildException.Problem problem : ex.problems()) {
                SourceSpan span = findSourceSpan(sourceMap, problem.path(), definition.span());
                diagnostics.add(new Diagnostic(problem.code(), problem.message(), span, problem.path()));
            }
            throw new FlowDiagnosticException(diagnostics);
        }

        return BoundFlow.builder()
                .flow(flow)
                .sourceMap(sourceMap)
                .metadata(definition.metadata())
                .inputType(typeCheckResult.inputType())
                .outputType(typeCheckResult.outputType())
                .build();
    }

    @SuppressWarnings("unchecked")
    private Flow<Object, Object> bindRoot(FlowSpec rootSpec, Map<String, SourceSpan> sourceMap) {
        Flow<Object, Object> flow = (Flow<Object, Object>) bindSpec(rootSpec);
        buildSourceMap(flow.root(), FlowPaths.root(), rootSpec, sourceMap);
        return flow;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Flow<?, ?> bindSpec(FlowSpec spec) {
        if (spec == null) {
            return Flow.identity();
        }

        if (spec instanceof StepSpec) {
            return bindStep((StepSpec) spec);
        } else if (spec instanceof SequenceSpec) {
            return bindSequence((SequenceSpec) spec);
        } else if (spec instanceof RouteSpec) {
            return bindRoute((RouteSpec) spec);
        } else if (spec instanceof FirstApplicableSpec) {
            return bindFirstApplicable((FirstApplicableSpec) spec);
        } else if (spec instanceof RecoverSpec) {
            return bindRecover((RecoverSpec) spec);
        } else if (spec instanceof ParallelSpec) {
            return bindParallel((ParallelSpec) spec);
        } else if (spec instanceof AwaitSpec) {
            return bindAwait((AwaitSpec) spec);
        } else if (spec instanceof CompleteSpec) {
            return bindComplete((CompleteSpec) spec);
        } else if (spec instanceof ControlSpec) {
            return bindControl((ControlSpec) spec);
        }

        return Flow.identity();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Flow<?, ?> bindStep(StepSpec step) {
        OperationDescriptor op = registry.operation(step.operation().id());
        if (op == null) {
            throw new FlowDiagnosticException(
                    "UNKNOWN_OPERATION", "Operation not found: " + step.operation().id());
        }

        SymbolRef projectRef = findProjector(step);
        SymbolRef mergeRef = findMerger(step);

        Function<Object, Object> projectFn = Function.identity();
        if (projectRef != null) {
            ProjectorDescriptor projDesc = registry.projector(projectRef.id());
            if (projDesc != null) {
                projectFn = projDesc.function();
            }
        }

        BiFunction<Object, Object, Object> mergeFn = (state, result) -> result;
        if (mergeRef != null) {
            MergerDescriptor mergeDesc = registry.merger(mergeRef.id());
            if (mergeDesc != null) {
                mergeFn = mergeDesc.function();
            }
        }

        Flow flow;
        if (projectRef != null || mergeRef != null) {
            if (op.instance() != null) {
                flow = Flow.identity().use((Operation) op.instance(), projectFn, mergeFn);
            } else {
                flow = Flow.identity().use((Class) op.contract(), op.qualifier(), projectFn, mergeFn);
            }
        } else {
            if (op.instance() != null) {
                flow = Flow.step((Operation) op.instance());
            } else {
                flow = Flow.step((Class) op.contract(), op.qualifier());
            }
        }

        // 应用 Step 上的 Modifier 列表（后声明的位于外层）
        for (ModifierSpec mod : step.modifiers()) {
            flow = applyModifier(flow, mod);
        }

        return flow;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Flow<?, ?> applyModifier(Flow flow, ModifierSpec mod) {
        if (mod instanceof OptionalModifierSpec) {
            return Flow.firstApplicable(flow, Flow.identity());
        } else if (mod instanceof PolicyModifierSpec) {
            PolicyModifierSpec policyMod = (PolicyModifierSpec) mod;
            return applyPolicy(flow, policyMod.policy().id(), policyMod.key(), policyMod.configuration());
        } else if (mod instanceof RetryModifierSpec) {
            RetryModifierSpec retryMod = (RetryModifierSpec) mod;
            return applyPolicy(flow, retryMod.retry().id(), null, retryMod.configuration());
        } else if (mod instanceof TimeoutModifierSpec) {
            return flow.timeout(((TimeoutModifierSpec) mod).duration());
        } else if (mod instanceof NamedModifierSpec) {
            return flow.named(((NamedModifierSpec) mod).name());
        }
        return flow;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Flow<?, ?> applyPolicy(
            Flow flow,
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
            Function<Object, Object> actualKeyFn = binding.keyProjection() != null && binding.keyProjection() != Function.identity()
                    ? binding.keyProjection()
                    : keyFn;
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
                    "UNKNOWN_POLICY", "Policy not found: " + policyId);
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

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Flow<?, ?> bindSequence(SequenceSpec seq) {
        if (seq.elements().isEmpty()) {
            return Flow.identity();
        }
        Flow flow = bindSpec(seq.elements().get(0));
        for (int i = 1; i < seq.elements().size(); i++) {
            flow = flow.then(bindSpec(seq.elements().get(i)));
        }
        if (seq.scopeName() != null) {
            flow = Flow.scope(seq.scopeName(), flow);
        }
        return flow;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Flow<?, ?> bindRoute(RouteSpec route) {
        OperationDescriptor selector = registry.operation(route.selector().id());
        if (selector == null) {
            throw new FlowDiagnosticException(
                    "UNKNOWN_OPERATION", "Route selector operation not found: " + route.selector().id());
        }

        Flow.RouteStart start;
        if (selector.instance() != null) {
            start = Flow.route((Operation) selector.instance());
        } else {
            start = Flow.route((Class) selector.contract(), selector.qualifier());
        }

        TypeCodec<?> codec = registry.typeCodec(selector.outputType());
        Flow.RouteCases cases = null;

        for (CaseSpec caseSpec : route.cases()) {
            Object decodedKey = codec.decode(caseSpec.literalKey());
            Flow branchFlow = bindSpec(caseSpec.branch());
            if (cases == null) {
                cases = start.caseOf(decodedKey, branchFlow);
            } else {
                cases = cases.caseOf(decodedKey, branchFlow);
            }
        }

        if (route.otherwise() != null) {
            Flow otherwiseFlow = bindSpec(route.otherwise());
            return cases != null ? cases.otherwise(otherwiseFlow) : start.otherwise(otherwiseFlow);
        } else {
            return cases != null ? cases.withoutOtherwise() : start.otherwise(Flow.skipped(Reason.of("NO_ROUTE", "No route matched")));
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Flow<?, ?> bindFirstApplicable(FirstApplicableSpec spec) {
        if (spec.branches().isEmpty()) {
            return Flow.identity();
        }
        if (spec.branches().size() == 1) {
            return bindSpec(spec.branches().get(0));
        }
        Flow first = bindSpec(spec.branches().get(0));
        Flow[] remaining = new Flow[spec.branches().size() - 1];
        for (int i = 1; i < spec.branches().size(); i++) {
            remaining[i - 1] = bindSpec(spec.branches().get(i));
        }
        return Flow.firstApplicable(first, remaining);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Flow<?, ?> bindRecover(RecoverSpec recover) {
        Flow body = bindSpec(recover.body());
        Flow fallback = bindSpec(recover.onFailure());
        return body.recoverWith(fallback);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Flow<?, ?> bindParallel(ParallelSpec parallel) {
        JoinDescriptor joinDesc = registry.join(parallel.join().id());
        if (joinDesc == null) {
            throw new FlowDiagnosticException(
                    "UNKNOWN_JOIN", "Join strategy not found: " + parallel.join().id());
        }

        List<Branch> branches = new ArrayList<Branch>();
        for (BranchSpec branchSpec : parallel.branches()) {
            branches.add(Branch.of(branchSpec.name(), bindSpec(branchSpec.flow())));
        }

        JoinStrategy<?> strategy = joinDesc.strategy();
        if (strategy == null && joinDesc.contract() != null) {
            try {
                strategy = joinDesc.contract().newInstance();
            } catch (Exception ex) {
                throw new IllegalStateException("Failed to instantiate join strategy: " + joinDesc.contract(), ex);
            }
        }

        return Flow.parallel(branches.toArray(new Branch[0])).join((JoinStrategy) strategy);
    }

    private Flow<?, ?> bindAwait(AwaitSpec await) {
        ResumeDescriptor resumeDesc = registry.resumePoint(await.resumePoint().id());
        if (resumeDesc == null) {
            throw new FlowDiagnosticException(
                    "UNKNOWN_RESUME_POINT", "Resume point not found: " + await.resumePoint().id());
        }
        return Flow.identity().await(ResumePoint.named(await.resumePoint().id()));
    }

    @SuppressWarnings("unchecked")
    private Flow<?, ?> bindComplete(CompleteSpec complete) {
        String code = complete.literal() != null ? complete.literal() : complete.kind().name();
        switch (complete.kind()) {
            case ACCEPTED:
                return Flow.accepted(complete.literal() != null ? complete.literal() : "");
            case REJECTED:
                return Flow.rejected(Reason.of(code, code));
            case SKIPPED:
                return Flow.skipped(Reason.of(code, code));
            case FAILED:
                return Flow.failed(Failure.of(code, code));
            default:
                return Flow.identity();
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Flow<?, ?> bindControl(ControlSpec control) {
        Flow bodyFlow = bindSpec(control.body());
        switch (control.kind()) {
            case TIMEOUT:
                return bodyFlow.timeout((Duration) control.configuration());
            case NAMED:
                return bodyFlow.named((String) control.configuration());
            case SCOPE:
                return Flow.scope((String) control.configuration(), bodyFlow);
            case POLICY:
            case RETRY:
                return applyPolicy(
                        bodyFlow,
                        control.symbol().id(),
                        control.key(),
                        (Map<String, Object>) control.configuration());
            default:
                return bodyFlow;
        }
    }

    private static void buildSourceMap(
            Logical node,
            String path,
            FlowSpec spec,
            Map<String, SourceSpan> sourceMap) {
        if (node == null || spec == null) {
            return;
        }
        sourceMap.put(path, spec.span());

        if (node instanceof Logical.Sequence && spec instanceof SequenceSpec) {
            Logical.Sequence seqNode = (Logical.Sequence) node;
            SequenceSpec seqSpec = (SequenceSpec) spec;
            for (int i = 0; i < seqNode.children().size(); i++) {
                FlowSpec childSpec = i < seqSpec.elements().size() ? seqSpec.elements().get(i) : spec;
                buildSourceMap(seqNode.children().get(i), FlowPaths.child(path, i), childSpec, sourceMap);
            }
        } else if (node instanceof Logical.Route && spec instanceof RouteSpec) {
            Logical.Route routeNode = (Logical.Route) node;
            RouteSpec routeSpec = (RouteSpec) spec;
            sourceMap.put(FlowPaths.selectorPath(path), routeSpec.selector().span());
            for (int i = 0; i < routeNode.cases().size(); i++) {
                CaseSpec caseSpec = i < routeSpec.cases().size() ? routeSpec.cases().get(i) : null;
                FlowSpec branchSpec = caseSpec != null ? caseSpec.branch() : spec;
                buildSourceMap(routeNode.cases().get(i).branch(), FlowPaths.routeCase(path, i), branchSpec, sourceMap);
            }
            if (routeNode.otherwise() != null && routeSpec.otherwise() != null) {
                buildSourceMap(routeNode.otherwise(), FlowPaths.routeOtherwise(path), routeSpec.otherwise(), sourceMap);
            }
        } else if (node instanceof Logical.Fallback && spec instanceof FirstApplicableSpec) {
            Logical.Fallback fbNode = (Logical.Fallback) node;
            FirstApplicableSpec faSpec = (FirstApplicableSpec) spec;
            for (int i = 0; i < fbNode.branches().size(); i++) {
                FlowSpec childSpec = i < faSpec.branches().size() ? faSpec.branches().get(i) : spec;
                buildSourceMap(fbNode.branches().get(i), FlowPaths.fallbackBranch(path, i), childSpec, sourceMap);
            }
        } else if (node instanceof Logical.Fallback && spec instanceof RecoverSpec) {
            Logical.Fallback fbNode = (Logical.Fallback) node;
            RecoverSpec recSpec = (RecoverSpec) spec;
            if (fbNode.branches().size() >= 2) {
                buildSourceMap(fbNode.branches().get(0), FlowPaths.fallbackBranch(path, 0), recSpec.body(), sourceMap);
                buildSourceMap(fbNode.branches().get(1), FlowPaths.fallbackBranch(path, 1), recSpec.onFailure(), sourceMap);
            }
        } else if (node instanceof Logical.Parallel && spec instanceof ParallelSpec) {
            Logical.Parallel parNode = (Logical.Parallel) node;
            ParallelSpec parSpec = (ParallelSpec) spec;
            for (int i = 0; i < parNode.branches().size(); i++) {
                BranchSpec branchSpec = i < parSpec.branches().size() ? parSpec.branches().get(i) : null;
                FlowSpec branchFlowSpec = branchSpec != null ? branchSpec.flow() : spec;
                buildSourceMap(parNode.branches().get(i).flow(), FlowPaths.parallelBranch(path, i), branchFlowSpec, sourceMap);
            }
        } else if (node instanceof Logical.Control) {
            Logical.Control ctrlNode = (Logical.Control) node;
            buildSourceMap(ctrlNode.body(), FlowPaths.controlBody(path), spec, sourceMap);
        } else if (node instanceof Logical.Named) {
            Logical.Named namedNode = (Logical.Named) node;
            buildSourceMap(namedNode.body(), path, spec, sourceMap);
        }
    }

    private static SourceSpan findSourceSpan(
            Map<String, SourceSpan> sourceMap,
            String path,
            SourceSpan defaultSpan) {
        if (path == null) {
            return defaultSpan;
        }
        SourceSpan exact = sourceMap.get(path);
        if (exact != null) {
            return exact;
        }
        // 回退为前缀匹配
        String current = path;
        while (current.contains("/")) {
            current = current.substring(0, current.lastIndexOf('/'));
            SourceSpan candidate = sourceMap.get(current);
            if (candidate != null) {
                return candidate;
            }
        }
        return defaultSpan;
    }

    private static SymbolRef findProjector(StepSpec step) {
        if (step.project() != null) {
            return step.project();
        }
        for (ModifierSpec mod : step.modifiers()) {
            if (mod instanceof ProjectModifierSpec) {
                return ((ProjectModifierSpec) mod).projector();
            }
        }
        return null;
    }

    private static SymbolRef findMerger(StepSpec step) {
        if (step.merge() != null) {
            return step.merge();
        }
        for (ModifierSpec mod : step.modifiers()) {
            if (mod instanceof MergeModifierSpec) {
                return ((MergeModifierSpec) mod).merger();
            }
        }
        return null;
    }
}
