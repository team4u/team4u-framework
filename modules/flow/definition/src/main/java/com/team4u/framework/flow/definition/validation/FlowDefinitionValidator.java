package com.team4u.framework.flow.definition.validation;

import com.team4u.framework.parser.SourceSpan;

import com.team4u.framework.flow.definition.diagnostic.Diagnostic;
import com.team4u.framework.flow.definition.diagnostic.DiagnosticCodes;
import com.team4u.framework.flow.definition.model.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * 流程定义静态结构校验器（Flow Definition Validator）。
 *
 * <p>用于在 TypeCheck 与 Binding 之前，对 {@link FlowDefinition} 及底层 AST 进行纯结构性与语义前置条件校验，
 * 确保外部来源（DSL、JSON、YAML 或编程式构造）的 FlowDefinition 满足不可变 IR 的全部结构约束，
 * 杜绝非法状态穿透到 Binding 阶段引发 NPE、CCE 或核心构建异常。</p>
 *
 * @author jay.wu
 */
public final class FlowDefinitionValidator {

    public static final int CURRENT_SCHEMA = 1;
    public static final int SUPPORTED_SCHEMA = 1;

    public static boolean isSupportedSchema(int schema) {
        return schema == 1;
    }

    private FlowDefinitionValidator() {
    }

    /**
     * 校验流程定义结构合规性。
     *
     * @param definition 流程定义实例
     * @return 诊断问题列表（若完全合规返回空列表）
     */
    public static List<Diagnostic> validate(FlowDefinition definition) {
        List<Diagnostic> diagnostics = new ArrayList<Diagnostic>();
        if (definition == null) {
            diagnostics.add(new Diagnostic(
                    DiagnosticCodes.INVALID_DEFINITION,
                    "Flow definition must not be null",
                    SourceSpan.UNKNOWN));
            return diagnostics;
        }

        SourceSpan span = definition.span() != null ? definition.span() : SourceSpan.UNKNOWN;

        // 1. Schema 校验
        if (!isSupportedSchema(definition.schema())) {
            diagnostics.add(new Diagnostic(
                    DiagnosticCodes.DSL_UNSUPPORTED_SCHEMA,
                    "Unsupported schema version: " + definition.schema() + " (supported: 1)",
                    span));
        }

        // 2. Flow ID 校验
        if (definition.id() == null || definition.id().trim().isEmpty()) {
            diagnostics.add(new Diagnostic(
                    DiagnosticCodes.INVALID_FLOW_ID,
                    "Flow ID must not be blank",
                    span));
        }

        // 3. Flow Version 校验
        if (definition.version() == null || definition.version().trim().isEmpty()) {
            diagnostics.add(new Diagnostic(
                    DiagnosticCodes.INVALID_FLOW_VERSION,
                    "Flow version must not be blank",
                    span));
        }

        // 4. Root AST 校验
        if (definition.root() == null) {
            diagnostics.add(new Diagnostic(
                    DiagnosticCodes.INVALID_DEFINITION,
                    "Flow root spec must not be null",
                    span));
            return diagnostics;
        }

        Set<FlowSpec> visited = Collections.newSetFromMap(new IdentityHashMap<FlowSpec, Boolean>());
        validateSpec(definition.root(), diagnostics, visited);
        return diagnostics;
    }

    private static void validateSpec(FlowSpec spec, List<Diagnostic> diagnostics, Set<FlowSpec> visited) {
        if (spec == null) {
            diagnostics.add(new Diagnostic(
                    DiagnosticCodes.INVALID_DEFINITION,
                    "Flow spec element must not be null",
                    SourceSpan.UNKNOWN));
            return;
        }

        if (!visited.add(spec)) {
            diagnostics.add(new Diagnostic(
                    DiagnosticCodes.INVALID_DEFINITION,
                    "Duplicate AST node detected: the same FlowSpec instance must not be reused across multiple positions in the AST",
                    spec.span() != null ? spec.span() : SourceSpan.UNKNOWN));
            return;
        }

        if (spec instanceof StepSpec) {
            validateStep((StepSpec) spec, diagnostics);
        } else if (spec instanceof CallSpec) {
            validateCall((CallSpec) spec, diagnostics);
        } else if (spec instanceof SequenceSpec) {
            validateSequence((SequenceSpec) spec, diagnostics, visited);
        } else if (spec instanceof RouteSpec) {
            validateRoute((RouteSpec) spec, diagnostics, visited);
        } else if (spec instanceof FirstApplicableSpec) {
            validateFirstApplicable((FirstApplicableSpec) spec, diagnostics, visited);
        } else if (spec instanceof ParallelSpec) {
            validateParallel((ParallelSpec) spec, diagnostics, visited);
        } else if (spec instanceof RecoverSpec) {
            validateRecover((RecoverSpec) spec, diagnostics, visited);
        } else if (spec instanceof AwaitSpec) {
            validateAwait((AwaitSpec) spec, diagnostics);
        } else if (spec instanceof CompleteSpec) {
            validateComplete((CompleteSpec) spec, diagnostics);
        } else if (spec instanceof ControlSpec) {
            validateControl((ControlSpec) spec, diagnostics, visited);
        }
    }

    private static void validateCall(CallSpec call, List<Diagnostic> diagnostics) {
        if (call.flow() == null || call.flow().id() == null || call.flow().id().trim().isEmpty()) {
            diagnostics.add(new Diagnostic(
                    DiagnosticCodes.UNKNOWN_FLOW,
                    "Call flow identifier must not be blank",
                    call.span()));
        }

        if (call.modifiers() != null) {
            int projectCount = 0;
            int mergeCount = 0;
            for (ModifierSpec mod : call.modifiers()) {
                if (mod instanceof ProjectModifierSpec) {
                    projectCount++;
                    if (projectCount > 1) {
                        diagnostics.add(new Diagnostic(
                                DiagnosticCodes.DUPLICATE_STEP_PROJECT,
                                "Duplicate 'project' declaration in call",
                                mod.span()));
                    }
                } else if (mod instanceof MergeModifierSpec) {
                    mergeCount++;
                    if (mergeCount > 1) {
                        diagnostics.add(new Diagnostic(
                                DiagnosticCodes.DUPLICATE_STEP_MERGE,
                                "Duplicate 'merge' declaration in call",
                                mod.span()));
                    }
                } else if (mod instanceof TimeoutModifierSpec) {
                    Duration d = ((TimeoutModifierSpec) mod).duration();
                    if (d == null || d.isNegative() || d.isZero()) {
                        diagnostics.add(new Diagnostic(
                                DiagnosticCodes.INVALID_CONTROL,
                                "Timeout modifier must specify a positive duration",
                                mod.span()));
                    }
                } else if (mod instanceof NamedModifierSpec) {
                    String name = ((NamedModifierSpec) mod).name();
                    if (name == null || name.trim().isEmpty()) {
                        diagnostics.add(new Diagnostic(
                                DiagnosticCodes.INVALID_CONTROL,
                                "Named modifier must specify a non-blank label",
                                mod.span()));
                    }
                } else if (mod instanceof PolicyModifierSpec) {
                    PolicyModifierSpec p = (PolicyModifierSpec) mod;
                    if (p.policy() == null || p.policy().id() == null || p.policy().id().trim().isEmpty()) {
                        diagnostics.add(new Diagnostic(
                                DiagnosticCodes.INVALID_CONTROL,
                                "Policy modifier must specify a policy reference",
                                mod.span()));
                    }
                } else if (mod instanceof RetryModifierSpec) {
                    RetryModifierSpec r = (RetryModifierSpec) mod;
                    if (r.retry() == null || r.retry().id() == null || r.retry().id().trim().isEmpty()) {
                        diagnostics.add(new Diagnostic(
                                DiagnosticCodes.INVALID_CONTROL,
                                "Retry modifier must specify a retry policy reference",
                                mod.span()));
                    }
                }
            }
        }
    }

    private static void validateStep(StepSpec step, List<Diagnostic> diagnostics) {
        if (step.operation() == null || step.operation().id() == null || step.operation().id().trim().isEmpty()) {
            diagnostics.add(new Diagnostic(
                    DiagnosticCodes.UNKNOWN_OPERATION,
                    "Step operation identifier must not be blank",
                    step.span()));
        }

        if (step.modifiers() != null) {
            int projectCount = 0;
            int mergeCount = 0;
            for (ModifierSpec mod : step.modifiers()) {
                if (mod instanceof ProjectModifierSpec) {
                    projectCount++;
                    if (projectCount > 1) {
                        diagnostics.add(new Diagnostic(
                                DiagnosticCodes.DUPLICATE_STEP_PROJECT,
                                "Duplicate 'project' declaration in step",
                                mod.span()));
                    }
                } else if (mod instanceof MergeModifierSpec) {
                    mergeCount++;
                    if (mergeCount > 1) {
                        diagnostics.add(new Diagnostic(
                                DiagnosticCodes.DUPLICATE_STEP_MERGE,
                                "Duplicate 'merge' declaration in step",
                                mod.span()));
                    }
                } else if (mod instanceof TimeoutModifierSpec) {
                    Duration d = ((TimeoutModifierSpec) mod).duration();
                    if (d == null || d.isNegative() || d.isZero()) {
                        diagnostics.add(new Diagnostic(
                                DiagnosticCodes.INVALID_CONTROL,
                                "Timeout modifier must specify a positive duration",
                                mod.span()));
                    }
                } else if (mod instanceof NamedModifierSpec) {
                    String name = ((NamedModifierSpec) mod).name();
                    if (name == null || name.trim().isEmpty()) {
                        diagnostics.add(new Diagnostic(
                                DiagnosticCodes.INVALID_CONTROL,
                                "Named modifier must specify a non-blank label",
                                mod.span()));
                    }
                } else if (mod instanceof PolicyModifierSpec) {
                    PolicyModifierSpec p = (PolicyModifierSpec) mod;
                    if (p.policy() == null || p.policy().id() == null || p.policy().id().trim().isEmpty()) {
                        diagnostics.add(new Diagnostic(
                                DiagnosticCodes.INVALID_CONTROL,
                                "Policy modifier must specify a policy reference",
                                mod.span()));
                    }
                } else if (mod instanceof RetryModifierSpec) {
                    RetryModifierSpec r = (RetryModifierSpec) mod;
                    if (r.retry() == null || r.retry().id() == null || r.retry().id().trim().isEmpty()) {
                        diagnostics.add(new Diagnostic(
                                DiagnosticCodes.INVALID_CONTROL,
                                "Retry modifier must specify a retry policy reference",
                                mod.span()));
                    }
                }
            }
        }
    }

    private static void validateSequence(SequenceSpec sequence, List<Diagnostic> diagnostics, Set<FlowSpec> visited) {
        if (sequence.elements() == null) {
            diagnostics.add(new Diagnostic(
                    DiagnosticCodes.INVALID_DEFINITION,
                    "Sequence elements must not be null",
                    sequence.span()));
            return;
        }
        for (FlowSpec child : sequence.elements()) {
            validateSpec(child, diagnostics, visited);
        }
    }

    private static void validateRoute(RouteSpec route, List<Diagnostic> diagnostics, Set<FlowSpec> visited) {
        if (route.selector() == null || route.selector().id() == null || route.selector().id().trim().isEmpty()) {
            diagnostics.add(new Diagnostic(
                    DiagnosticCodes.UNKNOWN_OPERATION,
                    "Route selector identifier must not be blank",
                    route.span()));
        }
        if (route.cases() == null) {
            diagnostics.add(new Diagnostic(
                    DiagnosticCodes.INVALID_DEFINITION,
                    "Route cases must not be null",
                    route.span()));
            return;
        }
        for (CaseSpec caseSpec : route.cases()) {
            if (caseSpec == null) {
                diagnostics.add(new Diagnostic(
                        DiagnosticCodes.INVALID_ROUTE_CASE,
                        "Route case must not be null",
                        route.span()));
                continue;
            }
            if (caseSpec.literalKey() == null) {
                diagnostics.add(new Diagnostic(
                        DiagnosticCodes.INVALID_ROUTE_CASE,
                        "Route case key must not be null",
                        caseSpec.span()));
            }
            if (caseSpec.branch() == null) {
                diagnostics.add(new Diagnostic(
                        DiagnosticCodes.INVALID_ROUTE_CASE,
                        "Route case branch must not be null",
                        caseSpec.span()));
            } else {
                validateSpec(caseSpec.branch(), diagnostics, visited);
            }
        }
        if (route.otherwise() != null) {
            validateSpec(route.otherwise(), diagnostics, visited);
        }
    }

    private static void validateFirstApplicable(FirstApplicableSpec spec, List<Diagnostic> diagnostics, Set<FlowSpec> visited) {
        if (spec.branches() == null || spec.branches().isEmpty()) {
            diagnostics.add(new Diagnostic(
                    DiagnosticCodes.EMPTY_FIRST_APPLICABLE,
                    "firstApplicable requires at least one branch",
                    spec.span()));
            return;
        }
        for (FlowSpec branch : spec.branches()) {
            validateSpec(branch, diagnostics, visited);
        }
    }

    private static void validateParallel(ParallelSpec parallel, List<Diagnostic> diagnostics, Set<FlowSpec> visited) {
        if (parallel.branches() == null || parallel.branches().isEmpty()) {
            diagnostics.add(new Diagnostic(
                    DiagnosticCodes.EMPTY_PARALLEL,
                    "parallel requires at least one branch",
                    parallel.span()));
        } else {
            for (BranchSpec branch : parallel.branches()) {
                if (branch == null) {
                    diagnostics.add(new Diagnostic(
                            DiagnosticCodes.INVALID_DEFINITION,
                            "Parallel branch must not be null",
                            parallel.span()));
                    continue;
                }
                if (branch.name() == null || branch.name().trim().isEmpty()) {
                    diagnostics.add(new Diagnostic(
                            DiagnosticCodes.INVALID_DEFINITION,
                            "Parallel branch name must not be blank",
                            branch.span()));
                }
                if (branch.flow() == null) {
                    diagnostics.add(new Diagnostic(
                            DiagnosticCodes.INVALID_DEFINITION,
                            "Parallel branch flow must not be null",
                            branch.span()));
                } else {
                    validateSpec(branch.flow(), diagnostics, visited);
                }
            }
        }

        JoinSpec joinSpec = parallel.joinSpec();
        if (joinSpec instanceof BuiltinJoinSpec) {
            BuiltinJoinSpec b = (BuiltinJoinSpec) joinSpec;
            if (b.kind() == BuiltinJoinSpec.Kind.QUORUM && b.quorumRequired() < 1) {
                diagnostics.add(new Diagnostic(
                        DiagnosticCodes.INVALID_QUORUM,
                        "Quorum join required must be at least 1, got: " + b.quorumRequired(),
                        b.span()));
            }
        } else if (parallel.join() == null || parallel.join().id() == null || parallel.join().id().trim().isEmpty()) {
            diagnostics.add(new Diagnostic(
                    DiagnosticCodes.UNKNOWN_JOIN,
                    "Parallel join strategy identifier must not be blank",
                    parallel.span()));
        }
    }


    private static void validateRecover(RecoverSpec recover, List<Diagnostic> diagnostics, Set<FlowSpec> visited) {
        if (recover.body() == null) {
            diagnostics.add(new Diagnostic(
                    DiagnosticCodes.INVALID_DEFINITION,
                    "Recover body must not be null",
                    recover.span()));
        } else {
            validateSpec(recover.body(), diagnostics, visited);
        }

        if (recover.onFailure() == null) {
            diagnostics.add(new Diagnostic(
                    DiagnosticCodes.INVALID_DEFINITION,
                    "Recover onFailure must not be null",
                    recover.span()));
        } else {
            validateSpec(recover.onFailure(), diagnostics, visited);
        }
    }

    private static void validateAwait(AwaitSpec await, List<Diagnostic> diagnostics) {
        if (await.resumePoint() == null || await.resumePoint().id() == null || await.resumePoint().id().trim().isEmpty()) {
            diagnostics.add(new Diagnostic(
                    DiagnosticCodes.UNKNOWN_RESUME_POINT,
                    "Await resume point identifier must not be blank",
                    await.span()));
        }
    }

    private static void validateComplete(CompleteSpec complete, List<Diagnostic> diagnostics) {
        if (complete.kind() == null) {
            diagnostics.add(new Diagnostic(
                    DiagnosticCodes.INVALID_DEFINITION,
                    "Complete kind must not be null",
                    complete.span()));
        }
    }

    private static void validateControl(ControlSpec control, List<Diagnostic> diagnostics, Set<FlowSpec> visited) {
        if (control.kind() == null) {
            diagnostics.add(new Diagnostic(
                    DiagnosticCodes.INVALID_CONTROL,
                    "Control kind must not be null",
                    control.span()));
        }

        if (control.body() == null) {
            diagnostics.add(new Diagnostic(
                    DiagnosticCodes.INVALID_CONTROL,
                    "Control body must not be null",
                    control.span()));
        } else {
            validateSpec(control.body(), diagnostics, visited);
        }

        if (control.kind() == ControlSpec.ControlKind.POLICY || control.kind() == ControlSpec.ControlKind.RETRY) {
            if (control.symbol() == null || control.symbol().id() == null || control.symbol().id().trim().isEmpty()) {
                diagnostics.add(new Diagnostic(
                        DiagnosticCodes.INVALID_CONTROL,
                        "Policy/retry control requires a valid symbol reference",
                        control.span()));
            }
        } else if (control.kind() == ControlSpec.ControlKind.TIMEOUT) {
            if (!(control.configuration() instanceof Duration)) {
                diagnostics.add(new Diagnostic(
                        DiagnosticCodes.INVALID_CONTROL,
                        "Timeout control requires a Duration configuration",
                        control.span()));
            } else {
                Duration d = (Duration) control.configuration();
                if (d.isNegative() || d.isZero()) {
                    diagnostics.add(new Diagnostic(
                            DiagnosticCodes.INVALID_CONTROL,
                            "Timeout control requires a positive duration",
                            control.span()));
                }
            }
        } else if (control.kind() == ControlSpec.ControlKind.NAMED || control.kind() == ControlSpec.ControlKind.SCOPE) {
            if (!(control.configuration() instanceof String) || ((String) control.configuration()).trim().isEmpty()) {
                diagnostics.add(new Diagnostic(
                        DiagnosticCodes.INVALID_CONTROL,
                        "Named/scope control requires a non-blank label",
                        control.span()));
            }
        }
    }
}
