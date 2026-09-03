package com.team4u.framework.flow.compiler;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import com.team4u.framework.flow.spi.ControlKind;
import com.team4u.framework.flow.spi.ExecutableBinding;
import com.team4u.framework.flow.spi.ExecutableFlowVisitor;
import com.team4u.framework.flow.spi.ExecutableParallelBranch;
import com.team4u.framework.flow.spi.ExecutableRouteCase;
import com.team4u.framework.flow.spi.FallbackTrigger;

/**
 * 物理执行计划节点投影策略实现族。
 *
 * @author jay.wu
 */
public final class PlanNodeProjectors {
    private PlanNodeProjectors() { }

    private static <R> R pop(ArrayList<R> stack) {
        return stack.remove(stack.size() - 1);
    }

    private static ExecutableBinding toExecutableBinding(PlanNode.BoundTarget target, ExecutableBinding.Kind kind) {
        if (target == null) return null;
        return new ExecutableBinding(target.instance(), target.contract(),
                target.implementation(), target.qualifier(), kind);
    }

    static final class InvokeProjector implements PlanNodeProjector<PlanNode.Invoke> {
        @Override
        public Class<? extends PlanNode> key() {
            return PlanNode.Invoke.class;
        }

        @Override
        public void pushChildren(PlanNode.Invoke node, ArrayDeque<ExecutableProjector.WorkItem> workStack) { }

        @Override
        public <R> R build(PlanNode.Invoke invoke, ArrayList<R> resultStack, ExecutableFlowVisitor<R> visitor) {
            ExecutableBinding binding = toExecutableBinding(invoke.operation(), ExecutableBinding.Kind.OPERATION);
            return visitor.visitInvoke(invoke.descriptor(), binding, invoke.project(), invoke.merge());
        }
    }

    static final class SequenceProjector implements PlanNodeProjector<PlanNode.Sequence> {
        @Override
        public Class<? extends PlanNode> key() {
            return PlanNode.Sequence.class;
        }

        @Override
        public void pushChildren(PlanNode.Sequence seq, ArrayDeque<ExecutableProjector.WorkItem> workStack) {
            List<PlanNode> children = seq.children();
            for (int i = children.size() - 1; i >= 0; i--) {
                workStack.addLast(new ExecutableProjector.WorkItem(children.get(i), false));
            }
        }

        @Override
        public <R> R build(PlanNode.Sequence seq, ArrayList<R> resultStack, ExecutableFlowVisitor<R> visitor) {
            int childCount = seq.children().size();
            List<R> childrenResults = new ArrayList<R>(childCount);
            for (int i = 0; i < childCount; i++) {
                childrenResults.add(null);
            }
            for (int i = childCount - 1; i >= 0; i--) {
                childrenResults.set(i, pop(resultStack));
            }
            return visitor.visitSequence(seq.descriptor(),
                    Collections.unmodifiableList(childrenResults),
                    Optional.ofNullable(seq.scopeName()));
        }
    }

    static final class RouteProjector implements PlanNodeProjector<PlanNode.Route> {
        @Override
        public Class<? extends PlanNode> key() {
            return PlanNode.Route.class;
        }

        @Override
        public void pushChildren(PlanNode.Route route, ArrayDeque<ExecutableProjector.WorkItem> workStack) {
            if (route.otherwise() != null) {
                workStack.addLast(new ExecutableProjector.WorkItem(route.otherwise(), false));
            }
            List<PlanNode.Route.RouteCase> cases = route.cases();
            for (int i = cases.size() - 1; i >= 0; i--) {
                workStack.addLast(new ExecutableProjector.WorkItem(cases.get(i).branch(), false));
            }
        }

        @Override
        public <R> R build(PlanNode.Route route, ArrayList<R> resultStack, ExecutableFlowVisitor<R> visitor) {
            Optional<R> otherwiseResult = Optional.empty();
            if (route.otherwise() != null) {
                otherwiseResult = Optional.of(pop(resultStack));
            }
            int caseCount = route.cases().size();
            List<ExecutableRouteCase<R>> caseResults = new ArrayList<ExecutableRouteCase<R>>(caseCount);
            for (int i = 0; i < caseCount; i++) {
                caseResults.add(null);
            }
            for (int i = caseCount - 1; i >= 0; i--) {
                R branchResult = pop(resultStack);
                caseResults.set(i, new ExecutableRouteCase<R>(route.cases().get(i).key(), branchResult));
            }
            ExecutableBinding selectorBinding = toExecutableBinding(route.selector().operation(), ExecutableBinding.Kind.OPERATION);
            return visitor.visitRoute(route.descriptor(), selectorBinding,
                    Collections.unmodifiableList(caseResults), otherwiseResult);
        }
    }

    static final class FallbackProjector implements PlanNodeProjector<PlanNode.Fallback> {
        @Override
        public Class<? extends PlanNode> key() {
            return PlanNode.Fallback.class;
        }

        @Override
        public void pushChildren(PlanNode.Fallback fallback, ArrayDeque<ExecutableProjector.WorkItem> workStack) {
            List<PlanNode> branches = fallback.branches();
            for (int i = branches.size() - 1; i >= 0; i--) {
                workStack.addLast(new ExecutableProjector.WorkItem(branches.get(i), false));
            }
        }

        @Override
        public <R> R build(PlanNode.Fallback fallback, ArrayList<R> resultStack, ExecutableFlowVisitor<R> visitor) {
            int branchCount = fallback.branches().size();
            List<R> branchResults = new ArrayList<R>(branchCount);
            for (int i = 0; i < branchCount; i++) {
                branchResults.add(null);
            }
            for (int i = branchCount - 1; i >= 0; i--) {
                branchResults.set(i, pop(resultStack));
            }
            FallbackTrigger trigger = fallback.trigger() == PlanNode.Fallback.Trigger.SKIPPED
                    ? FallbackTrigger.SKIPPED : FallbackTrigger.FAILED;
            return visitor.visitFallback(fallback.descriptor(), trigger,
                    Collections.unmodifiableList(branchResults));
        }
    }

    static final class ParallelProjector implements PlanNodeProjector<PlanNode.Parallel> {
        @Override
        public Class<? extends PlanNode> key() {
            return PlanNode.Parallel.class;
        }

        @Override
        public void pushChildren(PlanNode.Parallel parallel, ArrayDeque<ExecutableProjector.WorkItem> workStack) {
            List<PlanNode.ParallelBranch> branches = parallel.branches();
            for (int i = branches.size() - 1; i >= 0; i--) {
                workStack.addLast(new ExecutableProjector.WorkItem(branches.get(i).plan(), false));
            }
        }

        @Override
        public <R> R build(PlanNode.Parallel parallel, ArrayList<R> resultStack, ExecutableFlowVisitor<R> visitor) {
            int branchCount = parallel.branches().size();
            List<ExecutableParallelBranch<R>> branchResults = new ArrayList<ExecutableParallelBranch<R>>(branchCount);
            for (int i = 0; i < branchCount; i++) {
                branchResults.add(null);
            }
            for (int i = branchCount - 1; i >= 0; i--) {
                R branchPlanResult = pop(resultStack);
                branchResults.set(i, new ExecutableParallelBranch<R>(parallel.branches().get(i).token(), branchPlanResult));
            }
            ExecutableBinding joinBinding = toExecutableBinding(parallel.join(), ExecutableBinding.Kind.JOIN);
            return visitor.visitParallel(parallel.descriptor(),
                    Collections.unmodifiableList(branchResults), joinBinding);
        }
    }

    static final class AwaitProjector implements PlanNodeProjector<PlanNode.Await> {
        @Override
        public Class<? extends PlanNode> key() {
            return PlanNode.Await.class;
        }

        @Override
        public void pushChildren(PlanNode.Await node, ArrayDeque<ExecutableProjector.WorkItem> workStack) { }

        @Override
        public <R> R build(PlanNode.Await await, ArrayList<R> resultStack, ExecutableFlowVisitor<R> visitor) {
            return visitor.visitAwait(await.descriptor(), await.point());
        }
    }

    static final class ControlProjector implements PlanNodeProjector<PlanNode.Control> {
        @Override
        public Class<? extends PlanNode> key() {
            return PlanNode.Control.class;
        }

        @Override
        public void pushChildren(PlanNode.Control control, ArrayDeque<ExecutableProjector.WorkItem> workStack) {
            workStack.addLast(new ExecutableProjector.WorkItem(control.body(), false));
        }

        @Override
        public <R> R build(PlanNode.Control control, ArrayList<R> resultStack, ExecutableFlowVisitor<R> visitor) {
            R bodyResult = pop(resultStack);
            ControlKind kind;
            switch (control.kind()) {
                case POLICY:
                    kind = ControlKind.POLICY;
                    break;
                case PERSISTENT_POLICY:
                    kind = ControlKind.PERSISTENT_POLICY;
                    break;
                case TIMEOUT:
                    kind = ControlKind.TIMEOUT;
                    break;
                default:
                    throw new IllegalStateException("Unknown control kind: " + control.kind());
            }
            Optional<ExecutableBinding> binding = Optional.empty();
            if (control.policy() != null) {
                ExecutableBinding.Kind bKind = kind == ControlKind.PERSISTENT_POLICY
                        ? ExecutableBinding.Kind.PERSISTENT_POLICY : ExecutableBinding.Kind.POLICY;
                binding = Optional.of(toExecutableBinding(control.policy(), bKind));
            }
            return visitor.visitControl(control.descriptor(), kind, bodyResult, binding,
                    control.keyProjection(), control.configuration());
        }
    }

    static final class CompleteProjector implements PlanNodeProjector<PlanNode.Complete> {
        @Override
        public Class<? extends PlanNode> key() {
            return PlanNode.Complete.class;
        }

        @Override
        public void pushChildren(PlanNode.Complete node, ArrayDeque<ExecutableProjector.WorkItem> workStack) { }

        @Override
        public <R> R build(PlanNode.Complete complete, ArrayList<R> resultStack, ExecutableFlowVisitor<R> visitor) {
            return visitor.visitComplete(complete.descriptor(), complete.outcome(), complete.identity());
        }
    }

    static final class AdapterProjector implements PlanNodeProjector<PlanNode.Adapter> {
        @Override
        public Class<? extends PlanNode> key() {
            return PlanNode.Adapter.class;
        }

        @Override
        public void pushChildren(PlanNode.Adapter node, ArrayDeque<ExecutableProjector.WorkItem> workStack) {
            workStack.addLast(new ExecutableProjector.WorkItem(node.body(), false));
        }

        @Override
        public <R> R build(PlanNode.Adapter adapter, ArrayList<R> resultStack, ExecutableFlowVisitor<R> visitor) {
            R bodyResult = pop(resultStack);
            return visitor.visitAdapter(adapter.descriptor(), bodyResult, adapter.project(), adapter.merge());
        }
    }
}
