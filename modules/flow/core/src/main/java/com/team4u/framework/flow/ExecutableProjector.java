package com.team4u.framework.flow;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 工作列表后序迭代遍历 PlanNode 树并调用 {@link ExecutableFlowVisitor} 生成投影产物的内部投射器。
 *
 * <p>设计与算法保证：
 * <ul>
 *   <li><b>非递归堆栈遍历</b>：采用显式堆栈工作列表（Explicit Work Stack）实现后序遍历（Post-order Traversal），避免深度嵌套（如 5000+ 层 scope）时发生 JVM 方法栈溢出（{@link StackOverflowError}）；</li>
 *   <li><b>严格保序与非空</b>：确保子节点投影出栈顺序与 AST 原始声明顺序一致，并强制校验访问者产物非 null。</li>
 * </ul>
 * </p>
 *
 * @author jay.wu
 */
final class ExecutableProjector {
    private ExecutableProjector() { }

    /**
     * 工作任务元素。
     */
    private static final class WorkItem {
        final PlanNode node;
        final boolean build;

        WorkItem(PlanNode node, boolean build) {
            this.node = node;
            this.build = build;
        }
    }

    /**
     * 执行非递归投影。
     *
     * @param root    已编译校验的 PlanNode 根节点，不能为 null
     * @param visitor 投影访问者，不能为 null
     * @param <R>     产物类型
     * @return 最终根节点投影产物
     */
    static <R> R project(PlanNode root, ExecutableFlowVisitor<R> visitor) {
        Objects.requireNonNull(root, "root must not be null");
        Objects.requireNonNull(visitor, "visitor must not be null");

        ArrayDeque<WorkItem> workStack = new ArrayDeque<WorkItem>();
        ArrayList<R> resultStack = new ArrayList<R>();

        workStack.addLast(new WorkItem(root, false));

        while (!workStack.isEmpty()) {
            WorkItem current = workStack.removeLast();
            PlanNode node = current.node;

            if (current.build) {
                R projected = Objects.requireNonNull(buildNode(node, resultStack, visitor),
                        "visitor must not return null for node " + node.descriptor().path());
                resultStack.add(projected);
                continue;
            }

            // 压入 build 标记任务
            workStack.addLast(new WorkItem(node, true));

            // 将子节点逆序压入工作列表，以保证出栈顺序与子节点列表顺序一致
            pushChildren(node, workStack);
        }

        if (resultStack.size() != 1) {
            throw new IllegalStateException("Expected exactly 1 projection result on stack, but got: " + resultStack.size());
        }
        return resultStack.remove(resultStack.size() - 1);
    }

    private static void pushChildren(PlanNode node, ArrayDeque<WorkItem> workStack) {
        if (node instanceof PlanNode.Sequence) {
            PlanNode.Sequence seq = (PlanNode.Sequence) node;
            List<PlanNode> children = seq.children();
            for (int i = children.size() - 1; i >= 0; i--) {
                workStack.addLast(new WorkItem(children.get(i), false));
            }
        } else if (node instanceof PlanNode.Route) {
            PlanNode.Route route = (PlanNode.Route) node;
            if (route.otherwise() != null) {
                workStack.addLast(new WorkItem(route.otherwise(), false));
            }
            List<PlanNode.Route.RouteCase> cases = route.cases();
            for (int i = cases.size() - 1; i >= 0; i--) {
                workStack.addLast(new WorkItem(cases.get(i).branch(), false));
            }
        } else if (node instanceof PlanNode.Fallback) {
            PlanNode.Fallback fallback = (PlanNode.Fallback) node;
            List<PlanNode> branches = fallback.branches();
            for (int i = branches.size() - 1; i >= 0; i--) {
                workStack.addLast(new WorkItem(branches.get(i), false));
            }
        } else if (node instanceof PlanNode.Parallel) {
            PlanNode.Parallel parallel = (PlanNode.Parallel) node;
            List<PlanNode.ParallelBranch> branches = parallel.branches();
            for (int i = branches.size() - 1; i >= 0; i--) {
                workStack.addLast(new WorkItem(branches.get(i).plan(), false));
            }
        } else if (node instanceof PlanNode.Control) {
            PlanNode.Control control = (PlanNode.Control) node;
            workStack.addLast(new WorkItem(control.body(), false));
        }
    }

    private static <R> R pop(ArrayList<R> stack) {
        return stack.remove(stack.size() - 1);
    }

    private static <R> R buildNode(PlanNode node, ArrayList<R> resultStack, ExecutableFlowVisitor<R> visitor) {
        if (node instanceof PlanNode.Invoke) {
            PlanNode.Invoke invoke = (PlanNode.Invoke) node;
            ExecutableBinding binding = toExecutableBinding(invoke.operation(), ExecutableBinding.Kind.OPERATION);
            return visitor.visitInvoke(invoke.descriptor(), binding, invoke.project(), invoke.merge());
        } else if (node instanceof PlanNode.Sequence) {
            PlanNode.Sequence seq = (PlanNode.Sequence) node;
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
        } else if (node instanceof PlanNode.Route) {
            PlanNode.Route route = (PlanNode.Route) node;
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
        } else if (node instanceof PlanNode.Fallback) {
            PlanNode.Fallback fallback = (PlanNode.Fallback) node;
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
        } else if (node instanceof PlanNode.Parallel) {
            PlanNode.Parallel parallel = (PlanNode.Parallel) node;
            int branchCount = parallel.branches().size();
            List<ExecutableParallelBranch<R>> branchResults = new ArrayList<ExecutableParallelBranch<R>>(branchCount);
            for (int i = 0; i < branchCount; i++) {
                branchResults.add(null);
            }
            for (int i = branchCount - 1; i >= 0; i--) {
                R branchPlanResult = pop(resultStack);
                branchResults.set(i, new ExecutableParallelBranch<R>(parallel.branches().get(i).token(), branchPlanResult));
            }
            return visitor.visitParallel(parallel.descriptor(),
                    Collections.unmodifiableList(branchResults), parallel.join());
        } else if (node instanceof PlanNode.Await) {
            PlanNode.Await await = (PlanNode.Await) node;
            return visitor.visitAwait(await.descriptor(), await.point());
        } else if (node instanceof PlanNode.Control) {
            PlanNode.Control control = (PlanNode.Control) node;
            R bodyResult = pop(resultStack);
            ControlKind kind;
            switch (control.kind()) {
                case POLICY:
                    kind = ControlKind.POLICY;
                    break;
                case PERSISTENT_POLICY:
                    kind = ControlKind.PERSISTENT_POLICY;
                    break;
                case RETRY:
                    kind = ControlKind.RETRY;
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
        } else if (node instanceof PlanNode.Complete) {
            PlanNode.Complete complete = (PlanNode.Complete) node;
            return visitor.visitComplete(complete.descriptor(), complete.outcome(), complete.identity());
        } else {
            throw new IllegalStateException("Unknown PlanNode: " + node.getClass());
        }
    }

    private static ExecutableBinding toExecutableBinding(PlanNode.BoundTarget target, ExecutableBinding.Kind kind) {
        if (target == null) return null;
        return new ExecutableBinding(target.instance(), target.contract(),
                target.implementation(), target.qualifier(), kind);
    }
}

