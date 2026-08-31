package com.team4u.framework.flow.desc;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import com.team4u.framework.flow.compiler.FlowPaths;
import com.team4u.framework.flow.compiler.Logical;
import com.team4u.framework.flow.spi.BindingDescriptor;

/**
 * 逻辑 AST 结构描述生成策略实现族。
 *
 * @author jay.wu
 */
public final class LogicalDescribers {
    private LogicalDescribers() { }

    private static NodeDescription pop(ArrayList<NodeDescription> stack) {
        return stack.remove(stack.size() - 1);
    }

    private static BindingDescriptor describeBinding(Logical.Binding binding) {
        if (binding == null) return null;
        Class<?> contract = binding.contract();
        Class<?> impl = binding.instance() != null ? binding.instance().getClass() : null;
        return new BindingDescriptor(contract, impl, binding.qualifier(), binding.kind().name());
    }

    static final class InvokeDescriber implements LogicalDescriber<Logical.Invoke> {
        @Override
        public Class<? extends Logical> key() {
            return Logical.Invoke.class;
        }

        @Override
        public boolean isLeaf() {
            return true;
        }

        @Override
        public void pushChildren(Logical.Invoke logical, String path, ArrayDeque<FlowDescriptionBuilder.WorkItem> workStack) { }

        @Override
        public NodeDescription build(Logical.Invoke logical, String path, String label, ArrayList<NodeDescription> resultStack) {
            return NodeDescription.invoke(path, label, describeBinding(logical.binding()));
        }
    }

    static final class SequenceDescriber implements LogicalDescriber<Logical.Sequence> {
        @Override
        public Class<? extends Logical> key() {
            return Logical.Sequence.class;
        }

        @Override
        public boolean isLeaf() {
            return false;
        }

        @Override
        public void pushChildren(Logical.Sequence sequence, String path, ArrayDeque<FlowDescriptionBuilder.WorkItem> workStack) {
            List<Logical> children = sequence.children();
            for (int i = children.size() - 1; i >= 0; i--) {
                workStack.addLast(new FlowDescriptionBuilder.WorkItem(children.get(i), FlowPaths.child(path, i), null, false));
            }
        }

        @Override
        public NodeDescription build(Logical.Sequence sequence, String path, String label, ArrayList<NodeDescription> resultStack) {
            int childCount = sequence.children().size();
            List<NodeDescription> children = new ArrayList<NodeDescription>(childCount);
            for (int i = 0; i < childCount; i++) {
                children.add(null);
            }
            for (int i = childCount - 1; i >= 0; i--) {
                children.set(i, pop(resultStack));
            }
            return NodeDescription.sequence(path, label, children, sequence.scopeName());
        }
    }

    static final class RouteDescriber implements LogicalDescriber<Logical.Route> {
        @Override
        public Class<? extends Logical> key() {
            return Logical.Route.class;
        }

        @Override
        public boolean isLeaf() {
            return false;
        }

        @Override
        public void pushChildren(Logical.Route route, String path, ArrayDeque<FlowDescriptionBuilder.WorkItem> workStack) {
            if (route.otherwise() != null) {
                workStack.addLast(new FlowDescriptionBuilder.WorkItem(route.otherwise(), FlowPaths.routeOtherwise(path), null, false));
            }
            List<Logical.Route.Case> cases = route.cases();
            for (int i = cases.size() - 1; i >= 0; i--) {
                workStack.addLast(new FlowDescriptionBuilder.WorkItem(cases.get(i).branch(), FlowPaths.routeCase(path, i), null, false));
            }
        }

        @Override
        public NodeDescription build(Logical.Route route, String path, String label, ArrayList<NodeDescription> resultStack) {
            BindingDescriptor binding = describeBinding(route.selector());
            NodeDescription selector = NodeDescription.invoke(
                    FlowPaths.selectorPath(path), null, binding);

            NodeDescription otherwise = route.otherwise() == null ? null : pop(resultStack);

            int caseCount = route.cases().size();
            List<RouteCaseDescription> cases = new ArrayList<RouteCaseDescription>(caseCount);
            for (int i = 0; i < caseCount; i++) {
                cases.add(null);
            }
            for (int i = caseCount - 1; i >= 0; i--) {
                cases.set(i, new RouteCaseDescription(route.cases().get(i).key(), pop(resultStack)));
            }
            return NodeDescription.route(path, label, selector, cases, otherwise);
        }
    }

    static final class FallbackDescriber implements LogicalDescriber<Logical.Fallback> {
        @Override
        public Class<? extends Logical> key() {
            return Logical.Fallback.class;
        }

        @Override
        public boolean isLeaf() {
            return false;
        }

        @Override
        public void pushChildren(Logical.Fallback fallback, String path, ArrayDeque<FlowDescriptionBuilder.WorkItem> workStack) {
            List<Logical> branches = fallback.branches();
            for (int i = branches.size() - 1; i >= 0; i--) {
                workStack.addLast(new FlowDescriptionBuilder.WorkItem(branches.get(i), FlowPaths.fallbackBranch(path, i), null, false));
            }
        }

        @Override
        public NodeDescription build(Logical.Fallback fallback, String path, String label, ArrayList<NodeDescription> resultStack) {
            int branchCount = fallback.branches().size();
            List<NodeDescription> branches = new ArrayList<NodeDescription>(branchCount);
            for (int i = 0; i < branchCount; i++) {
                branches.add(null);
            }
            for (int i = branchCount - 1; i >= 0; i--) {
                branches.set(i, pop(resultStack));
            }
            return NodeDescription.fallback(path, label, fallback.trigger().name(), branches);
        }
    }

    static final class ParallelDescriber implements LogicalDescriber<Logical.Parallel> {
        @Override
        public Class<? extends Logical> key() {
            return Logical.Parallel.class;
        }

        @Override
        public boolean isLeaf() {
            return false;
        }

        @Override
        public void pushChildren(Logical.Parallel parallel, String path, ArrayDeque<FlowDescriptionBuilder.WorkItem> workStack) {
            List<Logical.ParallelBranch> branches = parallel.branches();
            for (int i = branches.size() - 1; i >= 0; i--) {
                workStack.addLast(new FlowDescriptionBuilder.WorkItem(branches.get(i).flow(), FlowPaths.parallelBranch(path, i), null, false));
            }
        }

        @Override
        public NodeDescription build(Logical.Parallel parallel, String path, String label, ArrayList<NodeDescription> resultStack) {
            int branchCount = parallel.branches().size();
            List<ParallelBranchDescription> branches = new ArrayList<ParallelBranchDescription>(branchCount);
            for (int i = 0; i < branchCount; i++) {
                branches.add(null);
            }
            for (int i = branchCount - 1; i >= 0; i--) {
                branches.set(i, new ParallelBranchDescription(
                        parallel.branches().get(i).token().name(), pop(resultStack)));
            }
            return NodeDescription.parallel(path, label, branches);
        }
    }

    static final class AwaitDescriber implements LogicalDescriber<Logical.Await> {
        @Override
        public Class<? extends Logical> key() {
            return Logical.Await.class;
        }

        @Override
        public boolean isLeaf() {
            return true;
        }

        @Override
        public void pushChildren(Logical.Await logical, String path, ArrayDeque<FlowDescriptionBuilder.WorkItem> workStack) { }

        @Override
        public NodeDescription build(Logical.Await await, String path, String label, ArrayList<NodeDescription> resultStack) {
            return NodeDescription.await(path, label, await.point().name());
        }
    }

    static final class ControlDescriber implements LogicalDescriber<Logical.Control> {
        @Override
        public Class<? extends Logical> key() {
            return Logical.Control.class;
        }

        @Override
        public boolean isLeaf() {
            return false;
        }

        @Override
        public void pushChildren(Logical.Control control, String path, ArrayDeque<FlowDescriptionBuilder.WorkItem> workStack) {
            workStack.addLast(new FlowDescriptionBuilder.WorkItem(control.body(), FlowPaths.controlBody(path), null, false));
        }

        @Override
        public NodeDescription build(Logical.Control control, String path, String label, ArrayList<NodeDescription> resultStack) {
            BindingDescriptor binding = control.binding() == null
                    ? null : describeBinding(control.binding());
            NodeDescription body = pop(resultStack);
            return NodeDescription.control(path, label, binding, body,
                    control.kind().name(), control.configuration());
        }
    }

    static final class CompleteDescriber implements LogicalDescriber<Logical.Complete> {
        @Override
        public Class<? extends Logical> key() {
            return Logical.Complete.class;
        }

        @Override
        public boolean isLeaf() {
            return true;
        }

        @Override
        public void pushChildren(Logical.Complete logical, String path, ArrayDeque<FlowDescriptionBuilder.WorkItem> workStack) { }

        @Override
        public NodeDescription build(Logical.Complete complete, String path, String label, ArrayList<NodeDescription> resultStack) {
            return complete.identity()
                    ? NodeDescription.identityComplete(path, label)
                    : NodeDescription.complete(path, label, complete.outcome());
        }
    }
}
