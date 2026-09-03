package com.team4u.framework.flow.definition.binding;

import com.team4u.framework.flow.Flow;
import com.team4u.framework.flow.Local;
import com.team4u.framework.flow.LocalExecutable;
import com.team4u.framework.flow.api.Branch;
import com.team4u.framework.flow.api.JoinStrategy;
import com.team4u.framework.flow.api.Operation;
import com.team4u.framework.flow.api.ResumePoint;
import com.team4u.framework.flow.definition.diagnostic.DiagnosticCodes;
import com.team4u.framework.flow.definition.diagnostic.FlowDiagnosticException;
import com.team4u.framework.flow.definition.model.*;
import com.team4u.framework.flow.definition.property.CompiledReader;
import com.team4u.framework.flow.definition.registry.*;
import com.team4u.framework.flow.definition.type.TypeCheckResult;
import com.team4u.framework.flow.definition.type.TypeChecker;
import com.team4u.framework.flow.definition.type.TypeCodec;
import com.team4u.framework.flow.Joins;
import com.team4u.framework.flow.definition.type.TypeRef;
import com.team4u.framework.flow.model.Failure;
import com.team4u.framework.flow.model.Outcome;
import com.team4u.framework.flow.model.Reason;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * 流程规范绑定器实现族。
 *
 * @author jay.wu
 */
public final class SpecBinders {

    private SpecBinders() {
    }

    public static final class StepSpecBinder implements SpecBinder<StepSpec> {
        @Override
        public Class<? extends FlowSpec> key() {
            return StepSpec.class;
        }

        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        public Flow<?, ?> bind(StepSpec step, BindingContext context) {
            OperationDescriptor op = context.registry().operation(step.operation().id());
            if (op == null) {
                throw new FlowDiagnosticException(
                        DiagnosticCodes.UNKNOWN_OPERATION, "Operation not found: " + step.operation().id());
            }

            TypeRef stateType = context.inputTypeOf(step);
            ProjectionSpec projectSpec = step.projectSpec();
            MergeSpec mergeSpec = step.mergeSpec();

            Function<Object, Object> projectFn = context.compileProjector(projectSpec, stateType);
            BiFunction<Object, Object, Object> mergeFn = context.compileMerger(mergeSpec, stateType, op.outputType());

            Flow flow;
            if (projectSpec != null || mergeSpec != null) {
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
                flow = applyModifier(flow, mod, context);
            }

            return flow;
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private Flow applyModifier(Flow flow, ModifierSpec mod, BindingContext context) {
            if (mod == null) {
                throw new FlowDiagnosticException(
                        DiagnosticCodes.INVALID_DEFINITION,
                        "Modifier must not be null");
            }
            if (mod instanceof OptionalModifierSpec) {
                return Flow.firstApplicable(flow, Flow.identity());
            } else if (mod instanceof PolicyModifierSpec) {
                PolicyModifierSpec policyMod = (PolicyModifierSpec) mod;
                return (Flow) context.applyPolicy(flow, policyMod.policy().id(), policyMod.key(), policyMod.configuration());
            } else if (mod instanceof RetryModifierSpec) {
                RetryModifierSpec retryMod = (RetryModifierSpec) mod;
                return (Flow) context.applyPolicy(flow, retryMod.retry().id(), null, retryMod.configuration());
            } else if (mod instanceof TimeoutModifierSpec) {
                return flow.timeout(((TimeoutModifierSpec) mod).duration());
            } else if (mod instanceof NamedModifierSpec) {
                return flow.named(((NamedModifierSpec) mod).name());
            }
            throw new FlowDiagnosticException(
                    DiagnosticCodes.UNSUPPORTED_MODIFIER_SPEC,
                    "Unsupported modifier spec: " + mod.getClass().getName(),
                    mod.span());
        }
    }

    public static final class CallSpecBinder implements SpecBinder<CallSpec> {
        @Override
        public Class<? extends FlowSpec> key() {
            return CallSpec.class;
        }

        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        public Flow<?, ?> bind(CallSpec call, BindingContext context) {
            TypeRef stateType = context.inputTypeOf(call);
            ProjectionSpec projectSpec = call.projectSpec();
            MergeSpec mergeSpec = call.mergeSpec();

            BindingContext.CompiledProjection compiledProj = context.compileCompiledProjection(projectSpec, stateType);
            Function<Object, Object> projectFn = compiledProj.projector();
            TypeRef callActualInputType = compiledProj.resultType();

            FlowDefinition subflowDef = context.subflow(call.flow().id());
            Flow subflow;
            TypeRef subflowOutputType = TypeRef.ANY;
            if (subflowDef != null) {
                BoundSubflow boundSubflow = context.bindSubflow(subflowDef, callActualInputType);
                subflow = boundSubflow.flow();
                subflowOutputType = boundSubflow.outputType();
            } else {
                OperationDescriptor op = context.registry().operation(call.flow().id());
                if (op == null) {
                    throw new FlowDiagnosticException(
                            DiagnosticCodes.UNKNOWN_FLOW, "Flow not found: " + call.flow().id());
                }
                subflowOutputType = op.outputType();
                if (op.instance() != null) {
                    subflow = Flow.step((Operation) op.instance());
                } else {
                    subflow = Flow.step((Class) op.contract(), op.qualifier());
                }
            }

            BiFunction<Object, Object, Object> mergeFn = context.compileMerger(mergeSpec, stateType, subflowOutputType);

            Flow flow;
            if (projectSpec != null || mergeSpec != null) {
                flow = Flow.adapt(subflow, projectFn, mergeFn);
            } else {
                flow = subflow;
            }

            // 应用 Call 上的 Modifier 列表
            for (ModifierSpec mod : call.modifiers()) {
                flow = applyModifier(flow, mod, context);
            }

            return flow;
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private Flow applyModifier(Flow flow, ModifierSpec mod, BindingContext context) {
            if (mod == null) {
                throw new FlowDiagnosticException(
                        DiagnosticCodes.INVALID_DEFINITION,
                        "Modifier must not be null");
            }
            if (mod instanceof OptionalModifierSpec) {
                return Flow.firstApplicable(flow, Flow.identity());
            } else if (mod instanceof PolicyModifierSpec) {
                PolicyModifierSpec policyMod = (PolicyModifierSpec) mod;
                return (Flow) context.applyPolicy(flow, policyMod.policy().id(), policyMod.key(), policyMod.configuration());
            } else if (mod instanceof RetryModifierSpec) {
                RetryModifierSpec retryMod = (RetryModifierSpec) mod;
                return (Flow) context.applyPolicy(flow, retryMod.retry().id(), null, retryMod.configuration());
            } else if (mod instanceof TimeoutModifierSpec) {
                return flow.timeout(((TimeoutModifierSpec) mod).duration());
            } else if (mod instanceof NamedModifierSpec) {
                return flow.named(((NamedModifierSpec) mod).name());
            }
            throw new FlowDiagnosticException(
                    DiagnosticCodes.UNSUPPORTED_MODIFIER_SPEC,
                    "Unsupported modifier spec: " + mod.getClass().getName(),
                    mod.span());
        }
    }

    public static final class SequenceSpecBinder implements SpecBinder<SequenceSpec> {
        @Override
        public Class<? extends FlowSpec> key() {
            return SequenceSpec.class;
        }

        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        public Flow<?, ?> bind(SequenceSpec seq, BindingContext context) {
            if (seq.elements().isEmpty()) {
                return Flow.identity();
            }
            Flow flow = context.bindSpec(seq.elements().get(0));
            for (int i = 1; i < seq.elements().size(); i++) {
                flow = flow.then(context.bindSpec(seq.elements().get(i)));
            }
            if (seq.scopeName() != null) {
                flow = Flow.scope(seq.scopeName(), flow);
            }
            return flow;
        }
    }

    public static final class RouteSpecBinder implements SpecBinder<RouteSpec> {
        @Override
        public Class<? extends FlowSpec> key() {
            return RouteSpec.class;
        }

        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        public Flow<?, ?> bind(RouteSpec route, BindingContext context) {
            OperationDescriptor selector = context.registry().operation(route.selector().id());
            if (selector == null) {
                throw new FlowDiagnosticException(
                        DiagnosticCodes.UNKNOWN_OPERATION, "Route selector operation not found: " + route.selector().id());
            }

            Flow.RouteStart start;
            if (selector.instance() != null) {
                start = Flow.route((Operation) selector.instance());
            } else {
                start = Flow.route((Class) selector.contract(), selector.qualifier());
            }

            TypeCodec<?> codec = context.registry().typeCodec(selector.outputType());
            if (codec == null) {
                throw new FlowDiagnosticException(
                        DiagnosticCodes.NO_TYPE_CODEC,
                        "No TypeCodec found for route selector output type: " + selector.outputType().typeName());
            }
            Flow.RouteCases cases = null;

            for (CaseSpec caseSpec : route.cases()) {
                Object decodedKey = codec.decode(caseSpec.literalKey());
                Flow branchFlow = context.bindSpec(caseSpec.branch());
                if (cases == null) {
                    cases = start.caseOf(decodedKey, branchFlow);
                } else {
                    cases = cases.caseOf(decodedKey, branchFlow);
                }
            }

            if (route.otherwise() != null) {
                Flow otherwiseFlow = context.bindSpec(route.otherwise());
                return cases != null ? cases.otherwise(otherwiseFlow) : start.otherwise(otherwiseFlow);
            } else {
                return cases != null ? cases.withoutOtherwise() : start.otherwise(Flow.skipped(Reason.of("NO_ROUTE", "No route matched")));
            }
        }
    }

    public static final class FirstApplicableSpecBinder implements SpecBinder<FirstApplicableSpec> {
        @Override
        public Class<? extends FlowSpec> key() {
            return FirstApplicableSpec.class;
        }

        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        public Flow<?, ?> bind(FirstApplicableSpec spec, BindingContext context) {
            if (spec.branches() == null || spec.branches().isEmpty()) {
                throw new FlowDiagnosticException(
                        DiagnosticCodes.EMPTY_FIRST_APPLICABLE,
                        "firstApplicable requires at least one branch",
                        spec.span());
            }
            if (spec.branches().size() == 1) {
                return context.bindSpec(spec.branches().get(0));
            }
            Flow first = context.bindSpec(spec.branches().get(0));
            Flow[] remaining = new Flow[spec.branches().size() - 1];
            for (int i = 1; i < spec.branches().size(); i++) {
                remaining[i - 1] = context.bindSpec(spec.branches().get(i));
            }
            return Flow.firstApplicable(first, remaining);
        }
    }

    public static final class RecoverSpecBinder implements SpecBinder<RecoverSpec> {
        @Override
        public Class<? extends FlowSpec> key() {
            return RecoverSpec.class;
        }

        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        public Flow<?, ?> bind(RecoverSpec recover, BindingContext context) {
            Flow body = context.bindSpec(recover.body());
            Flow fallback = context.bindSpec(recover.onFailure());
            return body.recoverWith(fallback);
        }
    }

    public static final class ParallelSpecBinder implements SpecBinder<ParallelSpec> {
        @Override
        public Class<? extends FlowSpec> key() {
            return ParallelSpec.class;
        }

        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        public Flow<?, ?> bind(ParallelSpec parallel, BindingContext context) {
            if (parallel.branches() == null || parallel.branches().isEmpty()) {
                throw new FlowDiagnosticException(
                        DiagnosticCodes.EMPTY_PARALLEL,
                        "parallel requires at least one branch",
                        parallel.span());
            }
            JoinSpec joinSpec = parallel.joinSpec();
            if (joinSpec == null) {
                throw new FlowDiagnosticException(
                        DiagnosticCodes.UNKNOWN_JOIN,
                        "Parallel join strategy must be specified",
                        parallel.span());
            }

            JoinStrategy<?> strategy = null;
            Class<? extends JoinStrategy<?>> strategyClass = null;
            String qualifier = null;
            if (joinSpec instanceof BuiltinJoinSpec) {
                BuiltinJoinSpec builtin = (BuiltinJoinSpec) joinSpec;
                int branchCount = parallel.branches() != null ? parallel.branches().size() : 0;
                switch (builtin.kind()) {
                    case ALL:
                        strategy = (JoinStrategy) Joins.allAcceptedBarrier();
                        break;
                    case FIRST:
                        strategy = Joins.firstAccepted();
                        break;
                    case COLLECT:
                        strategy = Joins.collect();
                        break;
                    case QUORUM:
                        if (builtin.quorumRequired() < 1 || builtin.quorumRequired() > branchCount) {
                            throw new FlowDiagnosticException(
                                    DiagnosticCodes.INVALID_QUORUM,
                                    "quorum required must be between 1 and " + branchCount + ", got: " + builtin.quorumRequired(),
                                    builtin.span());
                        }
                        strategy = (JoinStrategy) Joins.quorumBarrier(builtin.quorumRequired());
                        break;
                    default:
                        throw new FlowDiagnosticException(
                                DiagnosticCodes.UNSUPPORTED_BUILTIN_JOIN,
                                "Unsupported builtin join kind: " + builtin.kind(),
                                builtin.span());
                }
            } else if (joinSpec instanceof SymbolRef || joinSpec instanceof SymbolJoinSpec) {
                SymbolRef symbol = joinSpec instanceof SymbolRef
                        ? (SymbolRef) joinSpec
                        : ((SymbolJoinSpec) joinSpec).symbol();
                JoinDescriptor joinDesc = context.registry().join(symbol.id());
                if (joinDesc == null) {
                    throw new FlowDiagnosticException(
                            DiagnosticCodes.UNKNOWN_JOIN, "Join strategy not found: " + symbol.id(),
                            symbol.span());
                }

                if (joinDesc.strategy() != null) {
                    strategy = joinDesc.strategy();
                } else if (joinDesc.contract() != null) {
                    strategyClass = joinDesc.contract();
                    qualifier = joinDesc.qualifier();
                } else if (joinDesc.provider() != null) {
                    strategy = joinDesc.provider().provide(context.resolver());
                    if (strategy == null) {
                        throw new FlowDiagnosticException(
                                DiagnosticCodes.MISSING_BINDING,
                                "Cannot resolve join strategy for: " + symbol.id(),
                                symbol.span());
                    }
                } else {
                    throw new FlowDiagnosticException(
                            DiagnosticCodes.MISSING_BINDING,
                            "Cannot resolve join strategy for: " + symbol.id(),
                            symbol.span());
                }
            }

            List<Branch> branches = new ArrayList<Branch>();
            for (BranchSpec branchSpec : parallel.branches()) {
                branches.add(Branch.of(branchSpec.name(), context.bindSpec(branchSpec.flow())));
            }

            if (strategy != null) {
                return Flow.parallel(branches.toArray(new Branch[0])).join((JoinStrategy) strategy);
            } else {
                return Flow.parallel(branches.toArray(new Branch[0])).join((Class) strategyClass, qualifier);
            }
        }
    }


    public static final class AwaitSpecBinder implements SpecBinder<AwaitSpec> {
        @Override
        public Class<? extends FlowSpec> key() {
            return AwaitSpec.class;
        }

        @Override
        public Flow<?, ?> bind(AwaitSpec await, BindingContext context) {
            ResumeDescriptor resumeDesc = context.registry().resumePoint(await.resumePoint().id());
            if (resumeDesc == null) {
                throw new FlowDiagnosticException(
                        DiagnosticCodes.UNKNOWN_RESUME_POINT, "Resume point not found: " + await.resumePoint().id());
            }
            return Flow.identity().await(ResumePoint.named(await.resumePoint().id()));
        }
    }

    public static final class CompleteSpecBinder implements SpecBinder<CompleteSpec> {
        @Override
        public Class<? extends FlowSpec> key() {
            return CompleteSpec.class;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Flow<?, ?> bind(CompleteSpec complete, BindingContext context) {
            String code = complete.literal() != null ? complete.literal() : complete.kind().name();
            switch (complete.kind()) {
                case ACCEPTED:
                    return complete.literal() != null
                            ? Flow.accepted(complete.literal())
                            : Flow.identity();
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
    }

    public static final class ControlSpecBinder implements SpecBinder<ControlSpec> {
        @Override
        public Class<? extends FlowSpec> key() {
            return ControlSpec.class;
        }

        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        public Flow<?, ?> bind(ControlSpec control, BindingContext context) {
            Flow bodyFlow = context.bindSpec(control.body());
            switch (control.kind()) {
                case TIMEOUT:
                    return bodyFlow.timeout((Duration) control.configuration());
                case NAMED:
                    return bodyFlow.named((String) control.configuration());
                case SCOPE:
                    return Flow.scope((String) control.configuration(), bodyFlow);
                case POLICY:
                case RETRY:
                    if (control.symbol() == null || control.symbol().id() == null) {
                        throw new FlowDiagnosticException(
                                DiagnosticCodes.INVALID_CONTROL,
                                "Policy/retry control requires a valid symbol reference",
                                control.span());
                    }
                    return context.applyPolicy(
                            bodyFlow,
                            control.symbol().id(),
                            control.key(),
                            (Map<String, Object>) control.configuration());
                default:
                    return bodyFlow;
            }
        }
    }
}
