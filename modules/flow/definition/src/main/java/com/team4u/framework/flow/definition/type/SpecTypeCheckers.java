package com.team4u.framework.flow.definition.type;

import com.team4u.framework.flow.definition.diagnostic.Diagnostic;
import com.team4u.framework.flow.definition.diagnostic.DiagnosticCodes;
import com.team4u.framework.flow.definition.model.*;
import com.team4u.framework.flow.definition.registry.*;

/**
 * 流程规范静态类型检查器实现族。
 *
 * @author jay.wu
 */
public final class SpecTypeCheckers {

    private SpecTypeCheckers() {
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
                PolicyDescriptor policyDesc = context.registry().policy(policyMod.policy().id());
                if (policyDesc == null) {
                    context.addDiagnostic(new Diagnostic(
                            DiagnosticCodes.UNKNOWN_POLICY,
                            "Unknown policy: " + policyMod.policy().id(),
                            policyMod.policy().span()));
                }
            }
            for (RetryModifierSpec retryMod : step.retries()) {
                PolicyDescriptor policyDesc = context.registry().policy(retryMod.retry().id());
                if (policyDesc == null) {
                    context.addDiagnostic(new Diagnostic(
                            DiagnosticCodes.UNKNOWN_POLICY,
                            "Unknown retry policy: " + retryMod.retry().id(),
                            retryMod.retry().span()));
                }
            }

            return stepOutputType;
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

            TypeCodec<?> codec = context.registry().typeCodec(keyType);
            TypeRef branchOutputType = null;

            for (CaseSpec caseSpec : route.cases()) {
                if (keyType != TypeRef.ANY && codec != null) {
                    try {
                        codec.decode(caseSpec.literalKey());
                    } catch (Exception ex) {
                        context.addDiagnostic(new Diagnostic(
                                DiagnosticCodes.INVALID_ROUTE_CASE,
                                "Cannot decode route case key '" + caseSpec.literalKey() + "' for selector output type "
                                        + keyType.typeName() + ": " + ex.getMessage(),
                                caseSpec.span()));
                    }
                }
                TypeRef out = context.checkSpec(caseSpec.branch(), currentType);
                if (branchOutputType == null && out != TypeRef.ANY) {
                    branchOutputType = out;
                }
            }

            if (route.otherwise() != null) {
                TypeRef out = context.checkSpec(route.otherwise(), currentType);
                if (branchOutputType == null && out != TypeRef.ANY) {
                    branchOutputType = out;
                }
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
            TypeRef branchOutputType = null;
            for (FlowSpec branch : spec.branches()) {
                TypeRef out = context.checkSpec(branch, currentType);
                if (branchOutputType == null && out != TypeRef.ANY) {
                    branchOutputType = out;
                }
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

            if (bodyOut != TypeRef.ANY && fallbackOut != TypeRef.ANY && !bodyOut.isAssignableFrom(fallbackOut)) {
                context.addDiagnostic(new Diagnostic(
                        DiagnosticCodes.TYPE_MISMATCH,
                        "Recover onFailure branch output type " + fallbackOut.typeName()
                                + " is incompatible with body output type " + bodyOut.typeName(),
                        recover.onFailure().span()));
            }
            return bodyOut;
        }
    }

    public static final class ParallelSpecTypeChecker implements SpecTypeChecker<ParallelSpec> {
        @Override
        public Class<? extends FlowSpec> key() {
            return ParallelSpec.class;
        }

        @Override
        public TypeRef check(ParallelSpec parallel, TypeRef currentType, TypeCheckContext context) {
            JoinDescriptor joinDesc = context.registry().join(parallel.join().id());
            if (joinDesc == null) {
                context.addDiagnostic(new Diagnostic(
                        DiagnosticCodes.UNKNOWN_JOIN,
                        "Unknown join strategy: " + parallel.join().id(),
                        parallel.join().span()));
            }

            for (BranchSpec branch : parallel.branches()) {
                context.checkSpec(branch.flow(), currentType);
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
            return currentType;
        }
    }

    public static final class ControlSpecTypeChecker implements SpecTypeChecker<ControlSpec> {
        @Override
        public Class<? extends FlowSpec> key() {
            return ControlSpec.class;
        }

        @Override
        public TypeRef check(ControlSpec control, TypeRef currentType, TypeCheckContext context) {
            if (control.symbol() != null) {
                PolicyDescriptor policy = context.registry().policy(control.symbol().id());
                if (policy == null) {
                    context.addDiagnostic(new Diagnostic(
                            DiagnosticCodes.UNKNOWN_POLICY,
                            "Unknown policy: " + control.symbol().id(),
                            control.symbol().span()));
                }
            }
            return context.checkSpec(control.body(), currentType);
        }
    }
}
