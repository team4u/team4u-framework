package com.team4u.framework.flow.definition.type;

import com.team4u.framework.flow.definition.diagnostic.Diagnostic;
import com.team4u.framework.flow.definition.diagnostic.DiagnosticCodes;
import com.team4u.framework.flow.definition.model.*;
import com.team4u.framework.flow.definition.registry.*;

import java.util.*;

/**
 * 流程定义静态类型检查器（Type Checker）。
 *
 * <p>遍历 FlowSpec AST 校验节点间输入输出类型兼容性、符号存在性、修饰器约束及路由字面量可解析性。</p>
 *
 * @author jay.wu
 */
public final class TypeChecker {

    private final FlowDefinitionRegistry registry;

    public TypeChecker(FlowDefinitionRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
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
        return new TypeChecker(registry).check(definition, initialInputType);
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
        Objects.requireNonNull(definition, "flow definition must not be null");
        List<Diagnostic> diagnostics = new ArrayList<Diagnostic>();
        Map<FlowSpec, TypeRef> specInputTypes = new LinkedHashMap<FlowSpec, TypeRef>();
        Map<FlowSpec, TypeRef> specOutputTypes = new LinkedHashMap<FlowSpec, TypeRef>();

        TypeRef inputType = initialInputType != null
                ? initialInputType
                : inferInitialInputType(definition.root());

        TypeRef outputType = checkSpec(
                definition.root(),
                inputType,
                diagnostics,
                specInputTypes,
                specOutputTypes);

        boolean success = diagnostics.isEmpty();
        return TypeCheckResult.builder()
                .success(success)
                .diagnostics(diagnostics)
                .inputType(inputType)
                .outputType(outputType)
                .specInputTypes(specInputTypes)
                .specOutputTypes(specOutputTypes)
                .build();
    }

    private TypeRef inferInitialInputType(FlowSpec spec) {
        if (spec instanceof StepSpec) {
            StepSpec step = (StepSpec) spec;
            SymbolRef projectRef = findProjector(step);
            if (projectRef != null) {
                ProjectorDescriptor projector = registry.projector(projectRef.id());
                if (projector != null && projector.inputType() != TypeRef.ANY) {
                    return projector.inputType();
                }
            }
            OperationDescriptor op = registry.operation(step.operation().id());
            if (op != null && op.inputType() != TypeRef.ANY) {
                return op.inputType();
            }
        } else if (spec instanceof SequenceSpec) {
            SequenceSpec seq = (SequenceSpec) spec;
            if (!seq.elements().isEmpty()) {
                return inferInitialInputType(seq.elements().get(0));
            }
        } else if (spec instanceof RouteSpec) {
            RouteSpec route = (RouteSpec) spec;
            OperationDescriptor op = registry.operation(route.selector().id());
            if (op != null && op.inputType() != TypeRef.ANY) {
                return op.inputType();
            }
        } else if (spec instanceof RecoverSpec) {
            return inferInitialInputType(((RecoverSpec) spec).body());
        } else if (spec instanceof ControlSpec) {
            return inferInitialInputType(((ControlSpec) spec).body());
        }
        return TypeRef.ANY;
    }

    private TypeRef checkSpec(
            FlowSpec spec,
            TypeRef currentType,
            List<Diagnostic> diagnostics,
            Map<FlowSpec, TypeRef> specInputTypes,
            Map<FlowSpec, TypeRef> specOutputTypes) {
        if (spec == null) {
            return currentType;
        }
        specInputTypes.put(spec, currentType);
        TypeRef resultType;

        if (spec instanceof StepSpec) {
            resultType = checkStep((StepSpec) spec, currentType, diagnostics);
        } else if (spec instanceof SequenceSpec) {
            resultType = checkSequence((SequenceSpec) spec, currentType, diagnostics, specInputTypes, specOutputTypes);
        } else if (spec instanceof RouteSpec) {
            resultType = checkRoute((RouteSpec) spec, currentType, diagnostics, specInputTypes, specOutputTypes);
        } else if (spec instanceof FirstApplicableSpec) {
            resultType = checkFirstApplicable((FirstApplicableSpec) spec, currentType, diagnostics, specInputTypes, specOutputTypes);
        } else if (spec instanceof RecoverSpec) {
            resultType = checkRecover((RecoverSpec) spec, currentType, diagnostics, specInputTypes, specOutputTypes);
        } else if (spec instanceof ParallelSpec) {
            resultType = checkParallel((ParallelSpec) spec, currentType, diagnostics, specInputTypes, specOutputTypes);
        } else if (spec instanceof AwaitSpec) {
            resultType = checkAwait((AwaitSpec) spec, currentType, diagnostics);
        } else if (spec instanceof CompleteSpec) {
            resultType = checkComplete((CompleteSpec) spec, currentType, diagnostics);
        } else if (spec instanceof ControlSpec) {
            resultType = checkControl((ControlSpec) spec, currentType, diagnostics, specInputTypes, specOutputTypes);
        } else {
            resultType = currentType;
        }

        specOutputTypes.put(spec, resultType);
        return resultType;
    }

    private TypeRef checkStep(StepSpec step, TypeRef currentType, List<Diagnostic> diagnostics) {
        OperationDescriptor op = registry.operation(step.operation().id());
        if (op == null) {
            diagnostics.add(new Diagnostic(
                    DiagnosticCodes.UNKNOWN_OPERATION,
                    "Unknown operation: " + step.operation().id(),
                    step.operation().span()));
            return currentType;
        }

        SymbolRef projectRef = findProjector(step);
        SymbolRef mergeRef = findMerger(step);
        boolean isOptional = isOptional(step);

        TypeRef opActualInputType = currentType;
        TypeRef stepOutputType = op.outputType();

        // 校验 Projector
        if (projectRef != null) {
            ProjectorDescriptor projector = registry.projector(projectRef.id());
            if (projector == null) {
                diagnostics.add(new Diagnostic(
                        DiagnosticCodes.UNKNOWN_PROJECTOR,
                        "Unknown projector: " + projectRef.id(),
                        projectRef.span()));
            } else {
                if (currentType != TypeRef.ANY && projector.inputType() != TypeRef.ANY
                        && !projector.inputType().isAssignableFrom(currentType)) {
                    diagnostics.add(new Diagnostic(
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
            diagnostics.add(new Diagnostic(
                    DiagnosticCodes.TYPE_MISMATCH,
                    "Operation '" + step.operation().id() + "' expects input " + op.inputType().typeName()
                            + " but received " + opActualInputType.typeName(),
                    step.operation().span()));
        }

        // 校验 Merger
        if (mergeRef != null) {
            MergerDescriptor merger = registry.merger(mergeRef.id());
            if (merger == null) {
                diagnostics.add(new Diagnostic(
                        DiagnosticCodes.UNKNOWN_MERGER,
                        "Unknown merger: " + mergeRef.id(),
                        mergeRef.span()));
            } else {
                if (currentType != TypeRef.ANY && merger.stateType() != TypeRef.ANY
                        && !merger.stateType().isAssignableFrom(currentType)) {
                    diagnostics.add(new Diagnostic(
                            DiagnosticCodes.INVALID_MERGER,
                            "Merger '" + mergeRef.id() + "' expects state type " + merger.stateType().typeName()
                                    + " but current type is " + currentType.typeName(),
                            mergeRef.span()));
                }
                if (op.outputType() != TypeRef.ANY && merger.resultType() != TypeRef.ANY
                        && !merger.resultType().isAssignableFrom(op.outputType())) {
                    diagnostics.add(new Diagnostic(
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
                diagnostics.add(new Diagnostic(
                        DiagnosticCodes.INVALID_OPTIONAL_STEP,
                        "Optional step requires matching input and output type (expected "
                                + currentType.typeName() + " but got " + stepOutputType.typeName() + ")",
                        step.span()));
            }
            stepOutputType = currentType;
        }

        // 校验其他 Modifiers
        for (ModifierSpec mod : step.modifiers()) {
            if (mod instanceof PolicyModifierSpec) {
                PolicyModifierSpec policyMod = (PolicyModifierSpec) mod;
                PolicyDescriptor policyDesc = registry.policy(policyMod.policy().id());
                if (policyDesc == null) {
                    diagnostics.add(new Diagnostic(
                            DiagnosticCodes.UNKNOWN_POLICY,
                            "Unknown policy: " + policyMod.policy().id(),
                            policyMod.policy().span()));
                }
            } else if (mod instanceof RetryModifierSpec) {
                RetryModifierSpec retryMod = (RetryModifierSpec) mod;
                PolicyDescriptor policyDesc = registry.policy(retryMod.retry().id());
                if (policyDesc == null) {
                    diagnostics.add(new Diagnostic(
                            DiagnosticCodes.UNKNOWN_POLICY,
                            "Unknown retry policy: " + retryMod.retry().id(),
                            retryMod.retry().span()));
                }
            }
        }

        return stepOutputType;
    }

    private TypeRef checkSequence(
            SequenceSpec seq,
            TypeRef currentType,
            List<Diagnostic> diagnostics,
            Map<FlowSpec, TypeRef> specInputTypes,
            Map<FlowSpec, TypeRef> specOutputTypes) {
        TypeRef curr = currentType;
        for (FlowSpec child : seq.elements()) {
            curr = checkSpec(child, curr, diagnostics, specInputTypes, specOutputTypes);
        }
        return curr;
    }

    private TypeRef checkRoute(
            RouteSpec route,
            TypeRef currentType,
            List<Diagnostic> diagnostics,
            Map<FlowSpec, TypeRef> specInputTypes,
            Map<FlowSpec, TypeRef> specOutputTypes) {
        OperationDescriptor selector = registry.operation(route.selector().id());
        TypeRef keyType = TypeRef.ANY;
        if (selector == null) {
            diagnostics.add(new Diagnostic(
                    DiagnosticCodes.UNKNOWN_OPERATION,
                    "Unknown route selector operation: " + route.selector().id(),
                    route.selector().span()));
        } else {
            if (currentType != TypeRef.ANY && selector.inputType() != TypeRef.ANY
                    && !selector.inputType().isAssignableFrom(currentType)) {
                diagnostics.add(new Diagnostic(
                        DiagnosticCodes.TYPE_MISMATCH,
                        "Route selector '" + route.selector().id() + "' expects input "
                                + selector.inputType().typeName() + " but current type is " + currentType.typeName(),
                        route.selector().span()));
            }
            keyType = selector.outputType();
        }

        TypeCodec<?> codec = registry.typeCodec(keyType);
        TypeRef branchOutputType = null;

        for (CaseSpec caseSpec : route.cases()) {
            if (keyType != TypeRef.ANY && codec != null) {
                try {
                    codec.decode(caseSpec.literalKey());
                } catch (Exception ex) {
                    diagnostics.add(new Diagnostic(
                            DiagnosticCodes.INVALID_ROUTE_CASE,
                            "Cannot decode route case key '" + caseSpec.literalKey() + "' for selector output type "
                                    + keyType.typeName() + ": " + ex.getMessage(),
                            caseSpec.span()));
                }
            }
            TypeRef out = checkSpec(caseSpec.branch(), currentType, diagnostics, specInputTypes, specOutputTypes);
            if (branchOutputType == null && out != TypeRef.ANY) {
                branchOutputType = out;
            }
        }

        if (route.otherwise() != null) {
            TypeRef out = checkSpec(route.otherwise(), currentType, diagnostics, specInputTypes, specOutputTypes);
            if (branchOutputType == null && out != TypeRef.ANY) {
                branchOutputType = out;
            }
        }

        return branchOutputType != null ? branchOutputType : currentType;
    }

    private TypeRef checkFirstApplicable(
            FirstApplicableSpec spec,
            TypeRef currentType,
            List<Diagnostic> diagnostics,
            Map<FlowSpec, TypeRef> specInputTypes,
            Map<FlowSpec, TypeRef> specOutputTypes) {
        TypeRef branchOutputType = null;
        for (FlowSpec branch : spec.branches()) {
            TypeRef out = checkSpec(branch, currentType, diagnostics, specInputTypes, specOutputTypes);
            if (branchOutputType == null && out != TypeRef.ANY) {
                branchOutputType = out;
            }
        }
        return branchOutputType != null ? branchOutputType : currentType;
    }

    private TypeRef checkRecover(
            RecoverSpec recover,
            TypeRef currentType,
            List<Diagnostic> diagnostics,
            Map<FlowSpec, TypeRef> specInputTypes,
            Map<FlowSpec, TypeRef> specOutputTypes) {
        TypeRef bodyOut = checkSpec(recover.body(), currentType, diagnostics, specInputTypes, specOutputTypes);
        TypeRef recoveryInput = TypeRef.recovery(currentType);
        TypeRef fallbackOut = checkSpec(recover.onFailure(), recoveryInput, diagnostics, specInputTypes, specOutputTypes);

        if (bodyOut != TypeRef.ANY && fallbackOut != TypeRef.ANY && !bodyOut.isAssignableFrom(fallbackOut)) {
            diagnostics.add(new Diagnostic(
                    DiagnosticCodes.TYPE_MISMATCH,
                    "Recover onFailure branch output type " + fallbackOut.typeName()
                            + " is incompatible with body output type " + bodyOut.typeName(),
                    recover.onFailure().span()));
        }
        return bodyOut;
    }

    private TypeRef checkParallel(
            ParallelSpec parallel,
            TypeRef currentType,
            List<Diagnostic> diagnostics,
            Map<FlowSpec, TypeRef> specInputTypes,
            Map<FlowSpec, TypeRef> specOutputTypes) {
        JoinDescriptor joinDesc = registry.join(parallel.join().id());
        if (joinDesc == null) {
            diagnostics.add(new Diagnostic(
                    DiagnosticCodes.UNKNOWN_JOIN,
                    "Unknown join strategy: " + parallel.join().id(),
                    parallel.join().span()));
        }

        for (BranchSpec branch : parallel.branches()) {
            checkSpec(branch.flow(), currentType, diagnostics, specInputTypes, specOutputTypes);
        }

        return joinDesc != null ? joinDesc.outputType() : TypeRef.ANY;
    }

    private TypeRef checkAwait(AwaitSpec await, TypeRef currentType, List<Diagnostic> diagnostics) {
        ResumeDescriptor resumeDesc = registry.resumePoint(await.resumePoint().id());
        if (resumeDesc == null) {
            diagnostics.add(new Diagnostic(
                    DiagnosticCodes.UNKNOWN_RESUME_POINT,
                    "Unknown resume point: " + await.resumePoint().id(),
                    await.resumePoint().span()));
            return TypeRef.resumed(currentType, TypeRef.ANY);
        }
        return TypeRef.resumed(currentType, resumeDesc.signalType());
    }

    private TypeRef checkComplete(CompleteSpec complete, TypeRef currentType, List<Diagnostic> diagnostics) {
        if (complete.kind() == CompleteSpec.CompleteKind.ACCEPTED) {
            return complete.literal() != null ? TypeRef.of(String.class) : currentType;
        }
        return currentType;
    }

    private TypeRef checkControl(
            ControlSpec control,
            TypeRef currentType,
            List<Diagnostic> diagnostics,
            Map<FlowSpec, TypeRef> specInputTypes,
            Map<FlowSpec, TypeRef> specOutputTypes) {
        if (control.symbol() != null) {
            PolicyDescriptor policy = registry.policy(control.symbol().id());
            if (policy == null) {
                diagnostics.add(new Diagnostic(
                        DiagnosticCodes.UNKNOWN_POLICY,
                        "Unknown policy: " + control.symbol().id(),
                        control.symbol().span()));
            }
        }
        return checkSpec(control.body(), currentType, diagnostics, specInputTypes, specOutputTypes);
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

    private static boolean isOptional(StepSpec step) {
        for (ModifierSpec mod : step.modifiers()) {
            if (mod instanceof OptionalModifierSpec) {
                return true;
            }
        }
        return false;
    }
}
