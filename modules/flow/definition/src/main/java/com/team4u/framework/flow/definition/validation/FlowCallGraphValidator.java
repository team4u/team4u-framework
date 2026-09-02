package com.team4u.framework.flow.definition.validation;

import com.team4u.framework.flow.definition.diagnostic.Diagnostic;
import com.team4u.framework.flow.definition.diagnostic.DiagnosticCodes;
import com.team4u.framework.flow.definition.model.*;
import com.team4u.framework.flow.definition.registry.FlowDefinitionRegistry;
import com.team4u.framework.parser.SourceSpan;

import java.util.*;

/**
 * 流程调用图循环依赖校验器。
 *
 * <p>基于三色标记法在编译前执行深度优先搜索检测子流程调用闭环，
 * 精准产生 {@link DiagnosticCodes#CYCLIC_FLOW_CALL} 诊断，杜绝运行期递归爆栈异常。</p>
 *
 * @author jay.wu
 */
public final class FlowCallGraphValidator {

    private enum Color {
        WHITE, GRAY, BLACK
    }

    private FlowCallGraphValidator() {
    }

    /**
     * 校验指定根流程及其引用的所有子流程是否存在调用环。
     *
     * @param rootFlow 根流程定义
     * @param registry 符号注册表
     * @return 循环调用诊断列表（若无环则返回空列表）
     */
    public static List<Diagnostic> validate(FlowDefinition rootFlow, FlowDefinitionRegistry registry) {
        List<Diagnostic> diagnostics = new ArrayList<Diagnostic>();
        if (rootFlow == null || rootFlow.root() == null || registry == null) {
            return diagnostics;
        }

        Map<String, Color> colors = new HashMap<String, Color>();
        dfs(rootFlow, registry, colors, diagnostics);
        return diagnostics;
    }

    private static void dfs(
            FlowDefinition currentFlow,
            FlowDefinitionRegistry registry,
            Map<String, Color> colors,
            List<Diagnostic> diagnostics) {
        String flowId = currentFlow.id();
        colors.put(flowId, Color.GRAY);

        collectAndVisitCalls(currentFlow.root(), registry, colors, diagnostics);

        colors.put(flowId, Color.BLACK);
    }

    private static void collectAndVisitCalls(
            FlowSpec spec,
            FlowDefinitionRegistry registry,
            Map<String, Color> colors,
            List<Diagnostic> diagnostics) {
        if (spec == null) {
            return;
        }

        if (spec instanceof CallSpec) {
            CallSpec call = (CallSpec) spec;
            if (call.flow() != null && call.flow().id() != null) {
                String targetId = call.flow().id();
                Color color = colors.getOrDefault(targetId, Color.WHITE);
                if (color == Color.GRAY) {
                    SourceSpan span = call.span() != null && call.span() != SourceSpan.UNKNOWN
                            ? call.span()
                            : (call.flow().span() != null ? call.flow().span() : SourceSpan.UNKNOWN);
                    diagnostics.add(new Diagnostic(
                            DiagnosticCodes.CYCLIC_FLOW_CALL,
                            "Cyclic flow call detected: " + targetId,
                            span));
                } else if (color == Color.WHITE) {
                    FlowDefinition subflow = registry.subflow(targetId);
                    if (subflow != null && subflow.root() != null) {
                        dfs(subflow, registry, colors, diagnostics);
                    }
                }
            }
        } else if (spec instanceof SequenceSpec) {
            SequenceSpec seq = (SequenceSpec) spec;
            if (seq.elements() != null) {
                for (FlowSpec child : seq.elements()) {
                    collectAndVisitCalls(child, registry, colors, diagnostics);
                }
            }
        } else if (spec instanceof RouteSpec) {
            RouteSpec route = (RouteSpec) spec;
            if (route.cases() != null) {
                for (CaseSpec c : route.cases()) {
                    if (c != null) {
                        collectAndVisitCalls(c.branch(), registry, colors, diagnostics);
                    }
                }
            }
            if (route.otherwise() != null) {
                collectAndVisitCalls(route.otherwise(), registry, colors, diagnostics);
            }
        } else if (spec instanceof FirstApplicableSpec) {
            FirstApplicableSpec fa = (FirstApplicableSpec) spec;
            if (fa.branches() != null) {
                for (FlowSpec branch : fa.branches()) {
                    collectAndVisitCalls(branch, registry, colors, diagnostics);
                }
            }
        } else if (spec instanceof ParallelSpec) {
            ParallelSpec parallel = (ParallelSpec) spec;
            if (parallel.branches() != null) {
                for (BranchSpec b : parallel.branches()) {
                    if (b != null) {
                        collectAndVisitCalls(b.flow(), registry, colors, diagnostics);
                    }
                }
            }
        } else if (spec instanceof RecoverSpec) {
            RecoverSpec recover = (RecoverSpec) spec;
            collectAndVisitCalls(recover.body(), registry, colors, diagnostics);
            collectAndVisitCalls(recover.onFailure(), registry, colors, diagnostics);
        } else if (spec instanceof ControlSpec) {
            collectAndVisitCalls(((ControlSpec) spec).body(), registry, colors, diagnostics);
        }
    }
}
