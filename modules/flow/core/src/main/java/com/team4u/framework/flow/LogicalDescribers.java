package com.team4u.framework.flow;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 逻辑 AST 结构描述生成策略实现族。
 *
 * @author jay.wu
 */
final class LogicalDescribers {
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
            BindingDescriptor binding = describeBinding(logical.binding());
            return new NodeDescription(path, Optional.ofNullable(label), NodeDescriptor.Kind.INVOKE,
                    Optional.of(binding), null, null, null, null, null, null, null, null, null, null, false);
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
                workStack.addLast(new FlowDescriptionBuilder.WorkItem(children.get(i), path + "/" + i, null, false));
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
            return new NodeDescription(path, Optional.ofNullable(label), NodeDescriptor.Kind.SEQUENCE,
                    Optional.empty(), children, sequence.scopeName(), null, null, null, null, null, null, null, null, false);
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
                workStack.addLast(new FlowDescriptionBuilder.WorkItem(route.otherwise(), path + "/otherwise", null, false));
            }
            List<Logical.Route.Case> cases = route.cases();
            for (int i = cases.size() - 1; i >= 0; i--) {
                workStack.addLast(new FlowDescriptionBuilder.WorkItem(cases.get(i).branch(), path + "/case:" + i, null, false));
            }
        }

        @Override
        public NodeDescription build(Logical.Route route, String path, String label, ArrayList<NodeDescription> resultStack) {
            BindingDescriptor binding = describeBinding(route.selector());
            NodeDescription selector = new NodeDescription(path + "/selector", Optional.empty(),
                    NodeDescriptor.Kind.INVOKE, Optional.of(binding), null, null, null, null, null, null, null, null, null, null, false);

            NodeDescription otherwise = route.otherwise() == null ? null : pop(resultStack);

            int caseCount = route.cases().size();
            List<RouteCaseDescription> cases = new ArrayList<RouteCaseDescription>(caseCount);
            for (int i = 0; i < caseCount; i++) {
                cases.add(null);
            }
            for (int i = caseCount - 1; i >= 0; i--) {
                cases.set(i, new RouteCaseDescription(route.cases().get(i).key(), pop(resultStack)));
            }

            List<NodeDescription> children = new ArrayList<NodeDescription>();
            children.add(selector);
            for (RouteCaseDescription cd : cases) {
                children.add(cd.branch());
            }
            if (otherwise != null) {
                children.add(otherwise);
            }
            return new NodeDescription(path, Optional.ofNullable(label), NodeDescriptor.Kind.ROUTE,
                    Optional.empty(), children, null, null, cases, otherwise, null, null, null, null, null, false);
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
                workStack.addLast(new FlowDescriptionBuilder.WorkItem(branches.get(i), path + "/branch:" + i, null, false));
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
            return new NodeDescription(path, Optional.ofNullable(label), NodeDescriptor.Kind.FALLBACK,
                    Optional.empty(), branches, null, fallback.trigger().name(), null, null, null, null, null, null, null, false);
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
                workStack.addLast(new FlowDescriptionBuilder.WorkItem(branches.get(i).flow(), path + "/branch:" + i, null, false));
            }
        }

        @Override
        public NodeDescription build(Logical.Parallel parallel, String path, String label, ArrayList<NodeDescription> resultStack) {
            int branchCount = parallel.branches().size();
            List<ParallelBranchDescription> branches = new ArrayList<ParallelBranchDescription>(branchCount);
            List<NodeDescription> children = new ArrayList<NodeDescription>(branchCount);
            for (int i = 0; i < branchCount; i++) {
                branches.add(null);
                children.add(null);
            }
            for (int i = branchCount - 1; i >= 0; i--) {
                NodeDescription branchDesc = pop(resultStack);
                branches.set(i, new ParallelBranchDescription(parallel.branches().get(i).token().name(), branchDesc));
                children.set(i, branchDesc);
            }
            return new NodeDescription(path, Optional.ofNullable(label), NodeDescriptor.Kind.PARALLEL,
                    Optional.empty(), children, null, null, null, null, branches, null, null, null, null, false);
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
            return new NodeDescription(path, Optional.ofNullable(label), NodeDescriptor.Kind.AWAIT,
                    Optional.empty(), null, null, null, null, null, null, await.point().name(), null, null, null, false);
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
            workStack.addLast(new FlowDescriptionBuilder.WorkItem(control.body(), path + "/body", null, false));
        }

        @Override
        public NodeDescription build(Logical.Control control, String path, String label, ArrayList<NodeDescription> resultStack) {
            Optional<BindingDescriptor> binding = control.binding() == null
                    ? Optional.empty() : Optional.of(describeBinding(control.binding()));
            NodeDescription body = pop(resultStack);
            List<NodeDescription> children = Collections.singletonList(body);
            return new NodeDescription(path, Optional.ofNullable(label), NodeDescriptor.Kind.CONTROL,
                    binding, children, null, null, null, null, null, null, control.kind().name(),
                    control.configuration(), null, false);
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
            return new NodeDescription(path, Optional.ofNullable(label), NodeDescriptor.Kind.COMPLETE,
                    Optional.empty(), null, null, null, null, null, null, null, null, null,
                    complete.outcome(), complete.identity());
        }
    }
}
