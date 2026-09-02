package com.team4u.framework.flow.definition.type;

import com.team4u.framework.parser.SourceSpan;

import com.team4u.framework.flow.definition.diagnostic.Diagnostic;
import com.team4u.framework.flow.definition.diagnostic.DiagnosticCodes;
import com.team4u.framework.flow.definition.model.*;
import com.team4u.framework.flow.definition.registry.*;

import java.util.HashSet;
import java.util.Set;

/**
 * 流程规范静态类型检查器实现族。
 *
 * @author jay.wu
 */
public final class SpecTypeCheckers {

    private SpecTypeCheckers() {
    }

    /**
     * 校验策略治理配置在当前类型下的兼容性。
     *
     * @param currentType  当前 Flow 输入类型
     * @param policyRef    策略符号引用
     * @param keyRef       键提取符号引用（可为 null）
     * @param fallbackSpan 源码定位回退 Span
     * @param context      类型检查上下文
     */
    public static void validatePolicy(
            TypeRef currentType,
            SymbolRef policyRef,
            SymbolRef keyRef,
            SourceSpan fallbackSpan,
            TypeCheckContext context) {
        if (policyRef == null) {
            return;
        }
        PolicyDescriptor policyDesc = context.registry().policy(policyRef.id());
        if (policyDesc == null) {
            context.addDiagnostic(new Diagnostic(
                    DiagnosticCodes.UNKNOWN_POLICY,
                    "Unknown policy: " + policyRef.id(),
                    policyRef.span() != null && policyRef.span() != SourceSpan.UNKNOWN ? policyRef.span() : fallbackSpan));
            return;
        }

        if (keyRef != null) {
            KeyProjectionDescriptor keyDesc = context.registry().keyProjection(keyRef.id());
            ProjectorDescriptor projDesc = null;
            if (keyDesc == null) {
                projDesc = context.registry().projector(keyRef.id());
            }

            if (keyDesc == null && projDesc == null) {
                context.addDiagnostic(new Diagnostic(
                        DiagnosticCodes.UNKNOWN_KEY_PROJECTION,
                        "Unknown key projection: " + keyRef.id(),
                        keyRef.span() != null && keyRef.span() != SourceSpan.UNKNOWN ? keyRef.span() : fallbackSpan));
                return;
            }

            TypeRef keyInputType = keyDesc != null ? keyDesc.inputType() : projDesc.inputType();
            TypeRef keyOutputType = keyDesc != null ? keyDesc.keyType() : projDesc.outputType();

            // 1. 检查 currentType -> keyProjection.inputType
            if (currentType != TypeRef.ANY && keyInputType != TypeRef.ANY
                    && !keyInputType.isAssignableFrom(currentType)) {
                context.addDiagnostic(new Diagnostic(
                        DiagnosticCodes.TYPE_MISMATCH,
                        "Key projection '" + keyRef.id() + "' expects input " + keyInputType.typeName()
                                + " but current type is " + currentType.typeName(),
                        keyRef.span() != null && keyRef.span() != SourceSpan.UNKNOWN ? keyRef.span() : fallbackSpan));
            }

            // 2. 检查 keyProjection.keyType -> policy.keyType
            if (keyOutputType != TypeRef.ANY && policyDesc.keyType() != TypeRef.ANY
                    && !policyDesc.keyType().isAssignableFrom(keyOutputType)) {
                context.addDiagnostic(new Diagnostic(
                        DiagnosticCodes.TYPE_MISMATCH,
                        "Policy '" + policyRef.id() + "' expects key type " + policyDesc.keyType().typeName()
                                + " but key projection produces " + keyOutputType.typeName(),
                        keyRef.span() != null && keyRef.span() != SourceSpan.UNKNOWN ? keyRef.span() : fallbackSpan));
            }
        } else {
            // 无显式 key，检查 currentType -> policy.keyType
            if (currentType != TypeRef.ANY && policyDesc.keyType() != TypeRef.ANY
                    && !policyDesc.keyType().isAssignableFrom(currentType)) {
                context.addDiagnostic(new Diagnostic(
                        DiagnosticCodes.TYPE_MISMATCH,
                        "Policy '" + policyRef.id() + "' expects key type " + policyDesc.keyType().typeName()
                                + " but current type is " + currentType.typeName(),
                        policyRef.span() != null && policyRef.span() != SourceSpan.UNKNOWN ? policyRef.span() : fallbackSpan));
            }
        }
    }

    /**
     * 统一分支输出类型（Branch Join Unification）。
     *
     * @param currentOutput  已有分支累积输出类型（若为 null/ANY 则直接采纳新分支类型）
     * @param branchOutput   新分支输出类型
     * @param constructName  控制结构名称（用于错误提示）
     * @param span           新分支源码定位
     * @param context        类型检查上下文
     * @return 兼容归一化后的输出类型
     */
    public static TypeRef unifyBranchOutput(
            TypeRef currentOutput,
            TypeRef branchOutput,
            String constructName,
            SourceSpan span,
            TypeCheckContext context) {
        if (currentOutput == null || currentOutput == TypeRef.ANY) {
            return branchOutput != null ? branchOutput : TypeRef.ANY;
        }
        if (branchOutput == null || branchOutput == TypeRef.ANY) {
            return currentOutput;
        }
        if (currentOutput.isAssignableFrom(branchOutput)) {
            return currentOutput;
        }
        if (branchOutput.isAssignableFrom(currentOutput)) {
            return branchOutput;
        }
        context.addDiagnostic(new Diagnostic(
                DiagnosticCodes.TYPE_MISMATCH,
                constructName + " branch output type " + branchOutput.typeName()
                        + " is incompatible with branch output type " + currentOutput.typeName(),
                span));
        return currentOutput;
    }

    public static final class StepSpecTypeChecker implements SpecTypeChecker<StepSpec> {
        @Override
        public Class<? extends FlowSpec> key() {
            return StepSpec.class;
        }

        @Override
        public TypeRef check(StepSpec step, TypeRef currentType, TypeCheckContext context) {
            OperationDescriptor op = context.registry().operation(step.operation().id());
            if (op == null) {
                context.addDiagnostic(new Diagnostic(
                        DiagnosticCodes.UNKNOWN_OPERATION,
                        "Unknown operation: " + step.operation().id(),
                        step.operation().span()));
                return currentType;
            }

            SymbolRef projectRef = step.project();
            SymbolRef mergeRef = step.merge();
            boolean isOptional = step.isOptional();

            TypeRef opActualInputType = currentType;
            TypeRef stepOutputType = op.outputType();

            // 校验 Projector
            if (projectRef != null) {
                ProjectorDescriptor projector = context.registry().projector(projectRef.id());
                if (projector == null) {
                    context.addDiagnostic(new Diagnostic(
                            DiagnosticCodes.UNKNOWN_PROJECTOR,
                            "Unknown projector: " + projectRef.id(),
                            projectRef.span()));
                } else {
                    if (currentType != TypeRef.ANY && projector.inputType() != TypeRef.ANY
                            && !projector.inputType().isAssignableFrom(currentType)) {
                        context.addDiagnostic(new Diagnostic(
                                DiagnosticCodes.INVALID_PROJECTOR,
                                "Projector '" + projectRef.id() + "' expects input " + projector.inputType().typeName()
                                        + " but current type is " + currentType.typeName(),
                                projectRef.span()));
                    }
                    opActualInputType = projector.outputType();
                }
            }

            // 校验 Operation 入参类型
            if (opActualInputType != TypeRef.ANY && op.inputType() != TypeRef.ANY
                    && !op.inputType().isAssignableFrom(opActualInputType)) {
                context.addDiagnostic(new Diagnostic(
                        DiagnosticCodes.TYPE_MISMATCH,
                        "Operation '" + step.operation().id() + "' expects input " + op.inputType().typeName()
                                + " but received " + opActualInputType.typeName(),
                        step.operation().span()));
            }

            // 校验 Merger
            if (mergeRef != null) {
                MergerDescriptor merger = context.registry().merger(mergeRef.id());
                if (merger == null) {
                    context.addDiagnostic(new Diagnostic(
                            DiagnosticCodes.UNKNOWN_MERGER,
                            "Unknown merger: " + mergeRef.id(),
                            mergeRef.span()));
                } else {
                    if (currentType != TypeRef.ANY && merger.stateType() != TypeRef.ANY
                            && !merger.stateType().isAssignableFrom(currentType)) {
                        context.addDiagnostic(new Diagnostic(
                                DiagnosticCodes.INVALID_MERGER,
                                "Merger '" + mergeRef.id() + "' expects state type " + merger.stateType().typeName()
                                        + " but current type is " + currentType.typeName(),
                                mergeRef.span()));
                    }
                    if (op.outputType() != TypeRef.ANY && merger.resultType() != TypeRef.ANY
                            && !merger.resultType().isAssignableFrom(op.outputType())) {
                        context.addDiagnostic(new Diagnostic(
                                DiagnosticCodes.INVALID_MERGER,
                                "Merger '" + mergeRef.id() + "' expects result type " + merger.resultType().typeName()
                                        + " but operation outputs " + op.outputType().typeName(),
                                mergeRef.span()));
                    }
                    stepOutputType = merger.outputType();
                }
            }

            // 校验 Optional Step 类型要求（输入输出类型必须一致）
            if (isOptional) {
                if (currentType != TypeRef.ANY && stepOutputType != TypeRef.ANY
                        && !currentType.isAssignableFrom(stepOutputType)) {
                    context.addDiagnostic(new Diagnostic(
                            DiagnosticCodes.INVALID_OPTIONAL_STEP,
                            "Optional step requires matching input and output type (expected "
                                    + currentType.typeName() + " but got " + stepOutputType.typeName() + ")",
                            step.span()));
                }
                stepOutputType = currentType;
            }

            // 校验 Policies 与 Retries
            for (PolicyModifierSpec policyMod : step.policies()) {
                validatePolicy(currentType, policyMod.policy(), policyMod.key(), policyMod.span(), context);
            }
            for (RetryModifierSpec retryMod : step.retries()) {
                validatePolicy(currentType, retryMod.retry(), null, retryMod.span(), context);
            }

            return stepOutputType;
        }
    }

    public static final class CallSpecTypeChecker implements SpecTypeChecker<CallSpec> {
        @Override
        public Class<? extends FlowSpec> key() {
            return CallSpec.class;
        }

        @Override
        public TypeRef check(CallSpec call, TypeRef currentType, TypeCheckContext context) {
            FlowDefinition subflow = context.registry().subflow(call.flow().id());
            TypeRef subInputType = TypeRef.ANY;
            TypeRef subOutputType = TypeRef.ANY;

            if (subflow != null) {
                if (context.isVisiting(call.flow().id())) {
                    return currentType;
                }
                context.pushVisiting(call.flow().id());
                try {
                    TypeCheckResult subResult = TypeChecker.check(subflow, context.registry(), null, context.visitingFlows());
                    if (!subResult.success()) {
                        for (Diagnostic d : subResult.diagnostics()) {
                            context.addDiagnostic(d);
                        }
                    }
                    subInputType = subResult.inputType();
                    subOutputType = subResult.outputType();
                } finally {
                    context.popVisiting(call.flow().id());
                }
            } else {
                OperationDescriptor op = context.registry().operation(call.flow().id());
                if (op != null) {
                    subInputType = op.inputType();
                    subOutputType = op.outputType();
                } else {
                    context.addDiagnostic(new Diagnostic(
                            DiagnosticCodes.UNKNOWN_FLOW,
                            "Unknown flow: " + call.flow().id(),
                            call.flow().span()));
                    return currentType;
                }
            }

            SymbolRef projectRef = call.project();
            SymbolRef mergeRef = call.merge();
            boolean isOptional = call.isOptional();

            TypeRef callActualInputType = currentType;
            TypeRef callOutputType = subOutputType;

            // 校验 Projector
            if (projectRef != null) {
                ProjectorDescriptor projector = context.registry().projector(projectRef.id());
                if (projector == null) {
                    context.addDiagnostic(new Diagnostic(
                            DiagnosticCodes.UNKNOWN_PROJECTOR,
                            "Unknown projector: " + projectRef.id(),
                            projectRef.span()));
                } else {
                    if (currentType != TypeRef.ANY && projector.inputType() != TypeRef.ANY
                            && !projector.inputType().isAssignableFrom(currentType)) {
                        context.addDiagnostic(new Diagnostic(
                                DiagnosticCodes.INVALID_PROJECTOR,
                                "Projector '" + projectRef.id() + "' expects input " + projector.inputType().typeName()
                                        + " but current type is " + currentType.typeName(),
                                projectRef.span()));
                    }
                    callActualInputType = projector.outputType();
                }
            }

            // 校验 Flow 入参类型
            if (callActualInputType != TypeRef.ANY && subInputType != TypeRef.ANY
                    && !subInputType.isAssignableFrom(callActualInputType)) {
                context.addDiagnostic(new Diagnostic(
                        DiagnosticCodes.TYPE_MISMATCH,
                        "Flow '" + call.flow().id() + "' expects input " + subInputType.typeName()
                                + " but received " + callActualInputType.typeName(),
                        call.flow().span()));
            }

            // 校验 Merger
            if (mergeRef != null) {
                MergerDescriptor merger = context.registry().merger(mergeRef.id());
                if (merger == null) {
                    context.addDiagnostic(new Diagnostic(
                            DiagnosticCodes.UNKNOWN_MERGER,
                            "Unknown merger: " + mergeRef.id(),
                            mergeRef.span()));
                } else {
                    if (currentType != TypeRef.ANY && merger.stateType() != TypeRef.ANY
                            && !merger.stateType().isAssignableFrom(currentType)) {
                        context.addDiagnostic(new Diagnostic(
                                DiagnosticCodes.INVALID_MERGER,
                                "Merger '" + mergeRef.id() + "' expects state type " + merger.stateType().typeName()
                                        + " but current type is " + currentType.typeName(),
                                mergeRef.span()));
                    }
                    if (subOutputType != TypeRef.ANY && merger.resultType() != TypeRef.ANY
                            && !merger.resultType().isAssignableFrom(subOutputType)) {
                        context.addDiagnostic(new Diagnostic(
                                DiagnosticCodes.INVALID_MERGER,
                                "Merger '" + mergeRef.id() + "' expects result type " + merger.resultType().typeName()
                                        + " but subflow outputs " + subOutputType.typeName(),
                                mergeRef.span()));
                    }
                    callOutputType = merger.outputType();
                }
            }

            // 校验 Optional Call 类型要求（输入输出类型必须一致）
            if (isOptional) {
                if (currentType != TypeRef.ANY && callOutputType != TypeRef.ANY
                        && !currentType.isAssignableFrom(callOutputType)) {
                    context.addDiagnostic(new Diagnostic(
                            DiagnosticCodes.INVALID_OPTIONAL_STEP,
                            "Optional call requires matching input and output type (expected "
                                    + currentType.typeName() + " but got " + callOutputType.typeName() + ")",
                            call.span()));
                }
                callOutputType = currentType;
            }

            // 校验 Policies 与 Retries
            for (PolicyModifierSpec policyMod : call.policies()) {
                validatePolicy(currentType, policyMod.policy(), policyMod.key(), policyMod.span(), context);
            }
            for (RetryModifierSpec retryMod : call.retries()) {
                validatePolicy(currentType, retryMod.retry(), null, retryMod.span(), context);
            }

            return callOutputType;
        }
    }

    public static final class SequenceSpecTypeChecker implements SpecTypeChecker<SequenceSpec> {
        @Override
        public Class<? extends FlowSpec> key() {
            return SequenceSpec.class;
        }

        @Override
        public TypeRef check(SequenceSpec seq, TypeRef currentType, TypeCheckContext context) {
            TypeRef curr = currentType;
            for (FlowSpec child : seq.elements()) {
                curr = context.checkSpec(child, curr);
            }
            return curr;
        }
    }

    public static final class RouteSpecTypeChecker implements SpecTypeChecker<RouteSpec> {
        @Override
        public Class<? extends FlowSpec> key() {
            return RouteSpec.class;
        }

        @Override
        public TypeRef check(RouteSpec route, TypeRef currentType, TypeCheckContext context) {
            OperationDescriptor selector = context.registry().operation(route.selector().id());
            TypeRef keyType = TypeRef.ANY;
            if (selector == null) {
                context.addDiagnostic(new Diagnostic(
                        DiagnosticCodes.UNKNOWN_OPERATION,
                        "Unknown route selector operation: " + route.selector().id(),
                        route.selector().span()));
            } else {
                if (currentType != TypeRef.ANY && selector.inputType() != TypeRef.ANY
                        && !selector.inputType().isAssignableFrom(currentType)) {
                    context.addDiagnostic(new Diagnostic(
                            DiagnosticCodes.TYPE_MISMATCH,
                            "Route selector '" + route.selector().id() + "' expects input "
                                    + selector.inputType().typeName() + " but current type is " + currentType.typeName(),
                            route.selector().span()));
                }
                keyType = selector.outputType();
            }

            TypeCodec<?> codec = keyType != TypeRef.ANY ? context.registry().typeCodec(keyType) : null;
            if (keyType != TypeRef.ANY && codec == null) {
                context.addDiagnostic(new Diagnostic(
                        DiagnosticCodes.NO_TYPE_CODEC,
                        "No TypeCodec found for route selector key type: " + keyType.typeName(),
                        route.selector().span()));
            }

            Set<Object> seenKeys = new HashSet<Object>();
            TypeRef branchOutputType = null;

            for (CaseSpec caseSpec : route.cases()) {
                if (keyType != TypeRef.ANY && codec != null) {
                    try {
                        Object decodedKey = codec.decode(caseSpec.literalKey());
                        if (!seenKeys.add(decodedKey)) {
                            context.addDiagnostic(new Diagnostic(
                                    DiagnosticCodes.DUPLICATE_ROUTE_CASE,
                                    "Duplicate route case key: " + caseSpec.literalKey(),
                                    caseSpec.span()));
                        }
                    } catch (Exception ex) {
                        context.addDiagnostic(new Diagnostic(
                                DiagnosticCodes.INVALID_ROUTE_CASE,
                                "Cannot decode route case key '" + caseSpec.literalKey() + "' for selector output type "
                                        + keyType.typeName() + ": " + ex.getMessage(),
                                caseSpec.span()));
                    }
                }
                TypeRef out = context.checkSpec(caseSpec.branch(), currentType);
                branchOutputType = unifyBranchOutput(
                        branchOutputType,
                        out,
                        "Route",
                        caseSpec.branch().span() != null && caseSpec.branch().span() != SourceSpan.UNKNOWN ? caseSpec.branch().span() : caseSpec.span(),
                        context);
            }

            if (route.otherwise() != null) {
                TypeRef out = context.checkSpec(route.otherwise(), currentType);
                branchOutputType = unifyBranchOutput(
                        branchOutputType,
                        out,
                        "Route otherwise",
                        route.otherwise().span(),
                        context);
            }

            return branchOutputType != null ? branchOutputType : currentType;
        }
    }

    public static final class FirstApplicableSpecTypeChecker implements SpecTypeChecker<FirstApplicableSpec> {
        @Override
        public Class<? extends FlowSpec> key() {
            return FirstApplicableSpec.class;
        }

        @Override
        public TypeRef check(FirstApplicableSpec spec, TypeRef currentType, TypeCheckContext context) {
            if (spec.branches() == null || spec.branches().isEmpty()) {
                context.addDiagnostic(new Diagnostic(
                        DiagnosticCodes.EMPTY_FIRST_APPLICABLE,
                        "firstApplicable requires at least one branch",
                        spec.span()));
                return currentType;
            }
            TypeRef branchOutputType = null;
            for (FlowSpec branch : spec.branches()) {
                TypeRef out = context.checkSpec(branch, currentType);
                branchOutputType = unifyBranchOutput(branchOutputType, out, "FirstApplicable", branch.span(), context);
            }
            return branchOutputType != null ? branchOutputType : currentType;
        }
    }

    public static final class RecoverSpecTypeChecker implements SpecTypeChecker<RecoverSpec> {
        @Override
        public Class<? extends FlowSpec> key() {
            return RecoverSpec.class;
        }

        @Override
        public TypeRef check(RecoverSpec recover, TypeRef currentType, TypeCheckContext context) {
            TypeRef bodyOut = context.checkSpec(recover.body(), currentType);
            TypeRef recoveryInput = TypeRef.recovery(currentType);
            TypeRef fallbackOut = context.checkSpec(recover.onFailure(), recoveryInput);

            return unifyBranchOutput(bodyOut, fallbackOut, "Recover onFailure", recover.onFailure().span(), context);
        }
    }

    public static final class ParallelSpecTypeChecker implements SpecTypeChecker<ParallelSpec> {
        @Override
        public Class<? extends FlowSpec> key() {
            return ParallelSpec.class;
        }

        @Override
        public TypeRef check(ParallelSpec parallel, TypeRef currentType, TypeCheckContext context) {
            if (parallel.branches() == null || parallel.branches().isEmpty()) {
                context.addDiagnostic(new Diagnostic(
                        DiagnosticCodes.EMPTY_PARALLEL,
                        "parallel requires at least one branch",
                        parallel.span()));
            }

            JoinDescriptor joinDesc = parallel.join() != null
                    ? context.registry().join(parallel.join().id())
                    : null;
            if (joinDesc == null && parallel.join() != null) {
                context.addDiagnostic(new Diagnostic(
                        DiagnosticCodes.UNKNOWN_JOIN,
                        "Unknown join strategy: " + parallel.join().id(),
                        parallel.join().span()));
            }

            if (parallel.branches() != null) {
                Set<String> seenBranchNames = new HashSet<String>();
                for (BranchSpec branch : parallel.branches()) {
                    if (branch != null) {
                        if (!seenBranchNames.add(branch.name())) {
                            context.addDiagnostic(new Diagnostic(
                                    DiagnosticCodes.DUPLICATE_BRANCH,
                                    "Duplicate parallel branch name: " + branch.name(),
                                    branch.span()));
                        }
                        context.checkSpec(branch.flow(), currentType);
                    }
                }
            }

            return joinDesc != null ? joinDesc.outputType() : TypeRef.ANY;
        }
    }

    public static final class AwaitSpecTypeChecker implements SpecTypeChecker<AwaitSpec> {
        @Override
        public Class<? extends FlowSpec> key() {
            return AwaitSpec.class;
        }

        @Override
        public TypeRef check(AwaitSpec await, TypeRef currentType, TypeCheckContext context) {
            ResumeDescriptor resumeDesc = context.registry().resumePoint(await.resumePoint().id());
            if (resumeDesc == null) {
                context.addDiagnostic(new Diagnostic(
                        DiagnosticCodes.UNKNOWN_RESUME_POINT,
                        "Unknown resume point: " + await.resumePoint().id(),
                        await.resumePoint().span()));
                return TypeRef.resumed(currentType, TypeRef.ANY);
            }
            return TypeRef.resumed(currentType, resumeDesc.signalType());
        }
    }

    public static final class CompleteSpecTypeChecker implements SpecTypeChecker<CompleteSpec> {
        @Override
        public Class<? extends FlowSpec> key() {
            return CompleteSpec.class;
        }

        @Override
        public TypeRef check(CompleteSpec complete, TypeRef currentType, TypeCheckContext context) {
            if (complete.kind() == CompleteSpec.CompleteKind.ACCEPTED) {
                return complete.literal() != null ? TypeRef.of(String.class) : currentType;
            }
            return TypeRef.ANY;
        }
    }

    public static final class ControlSpecTypeChecker implements SpecTypeChecker<ControlSpec> {
        @Override
        public Class<? extends FlowSpec> key() {
            return ControlSpec.class;
        }

        @Override
        public TypeRef check(ControlSpec control, TypeRef currentType, TypeCheckContext context) {
            if (control.kind() == ControlSpec.ControlKind.POLICY || control.kind() == ControlSpec.ControlKind.RETRY) {
                if (control.symbol() != null) {
                    validatePolicy(currentType, control.symbol(), control.key(), control.span(), context);
                } else {
                    context.addDiagnostic(new Diagnostic(
                            DiagnosticCodes.INVALID_CONTROL,
                            "Policy/retry control requires a valid symbol reference",
                            control.span()));
                }
            }
            return context.checkSpec(control.body(), currentType);
        }
    }
}
