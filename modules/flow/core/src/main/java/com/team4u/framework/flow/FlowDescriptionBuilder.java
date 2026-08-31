package com.team4u.framework.flow;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 遍历 Logical 树并生成 NodeDescription 树的内部构建器。
 * 采用显式工作栈后序构建，支持任意深度的嵌套流结构而不会发生栈溢出。
 */
final class FlowDescriptionBuilder {
    private FlowDescriptionBuilder() { }

    private static final class WorkItem {
        final Logical logical;
        final String path;
        final String label;
        final boolean build;

        WorkItem(Logical logical, String path, String label, boolean build) {
            this.logical = logical;
            this.path = path;
            this.label = label;
            this.build = build;
        }
    }

    static NodeDescription describe(Logical rootLogical, String rootPath) {
        ArrayDeque<WorkItem> workStack = new ArrayDeque<WorkItem>();
        ArrayList<NodeDescription> resultStack = new ArrayList<NodeDescription>();

        workStack.addLast(new WorkItem(rootLogical, rootPath, null, false));

        while (!workStack.isEmpty()) {
            WorkItem item = workStack.removeLast();
            Logical logical = item.logical;
            String path = item.path;

            if (item.build) {
                NodeDescription built = buildDescription(logical, path, item.label, resultStack);
                resultStack.add(built);
                continue;
            }

            String label = item.label;
            while (logical instanceof Logical.Named) {
                Logical.Named named = (Logical.Named) logical;
                label = named.label();
                logical = named.body();
            }

            if (logical instanceof Logical.Invoke || logical instanceof Logical.Await || logical instanceof Logical.Complete) {
                NodeDescription built = buildDescription(logical, path, label, resultStack);
                resultStack.add(built);
            } else if (logical instanceof Logical.Sequence) {
                Logical.Sequence sequence = (Logical.Sequence) logical;
                workStack.addLast(new WorkItem(logical, path, label, true));
                List<Logical> children = sequence.children();
                for (int i = children.size() - 1; i >= 0; i--) {
                    workStack.addLast(new WorkItem(children.get(i), path + "/" + i, null, false));
                }
            } else if (logical instanceof Logical.Route) {
                Logical.Route route = (Logical.Route) logical;
                workStack.addLast(new WorkItem(logical, path, label, true));
                if (route.otherwise() != null) {
                    workStack.addLast(new WorkItem(route.otherwise(), path + "/otherwise", null, false));
                }
                List<Logical.Route.Case> cases = route.cases();
                for (int i = cases.size() - 1; i >= 0; i--) {
                    workStack.addLast(new WorkItem(cases.get(i).branch(), path + "/case:" + i, null, false));
                }
            } else if (logical instanceof Logical.Fallback) {
                Logical.Fallback fallback = (Logical.Fallback) logical;
                workStack.addLast(new WorkItem(logical, path, label, true));
                List<Logical> branches = fallback.branches();
                for (int i = branches.size() - 1; i >= 0; i--) {
                    workStack.addLast(new WorkItem(branches.get(i), path + "/branch:" + i, null, false));
                }
            } else if (logical instanceof Logical.Parallel) {
                Logical.Parallel parallel = (Logical.Parallel) logical;
                workStack.addLast(new WorkItem(logical, path, label, true));
                List<Logical.ParallelBranch> branches = parallel.branches();
                for (int i = branches.size() - 1; i >= 0; i--) {
                    workStack.addLast(new WorkItem(branches.get(i).flow(), path + "/branch:" + i, null, false));
                }
            } else if (logical instanceof Logical.Control) {
                Logical.Control control = (Logical.Control) logical;
                workStack.addLast(new WorkItem(logical, path, label, true));
                workStack.addLast(new WorkItem(control.body(), path + "/body", null, false));
            } else {
                throw new IllegalStateException("Unknown logical node: " + logical.getClass());
            }
        }

        if (resultStack.size() != 1) {
            throw new IllegalStateException("Expected exactly 1 root description on stack, but got: " + resultStack.size());
        }
        return resultStack.remove(resultStack.size() - 1);
    }

    private static NodeDescription pop(ArrayList<NodeDescription> stack) {
        return stack.remove(stack.size() - 1);
    }

    private static NodeDescription buildDescription(Logical logical, String path, String label,
                                                    ArrayList<NodeDescription> resultStack) {
        Optional<String> optLabel = Optional.ofNullable(label);

        if (logical instanceof Logical.Invoke) {
            Logical.Invoke invoke = (Logical.Invoke) logical;
            BindingDescriptor binding = describeBinding(invoke.binding());
            return new NodeDescription(path, optLabel, NodeDescriptor.Kind.INVOKE,
                    Optional.of(binding), null, null, null, null, null, null, null, null, null, null, false);
        } else if (logical instanceof Logical.Sequence) {
            Logical.Sequence sequence = (Logical.Sequence) logical;
            int childCount = sequence.children().size();
            List<NodeDescription> children = new ArrayList<NodeDescription>(childCount);
            for (int i = 0; i < childCount; i++) {
                children.add(null);
            }
            for (int i = childCount - 1; i >= 0; i--) {
                children.set(i, pop(resultStack));
            }
            return new NodeDescription(path, optLabel, NodeDescriptor.Kind.SEQUENCE,
                    Optional.empty(), children, sequence.scopeName(), null, null, null, null, null, null, null, null, false);
        } else if (logical instanceof Logical.Route) {
            Logical.Route route = (Logical.Route) logical;
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
            return new NodeDescription(path, optLabel, NodeDescriptor.Kind.ROUTE,
                    Optional.empty(), children, null, null, cases, otherwise, null, null, null, null, null, false);
        } else if (logical instanceof Logical.Fallback) {
            Logical.Fallback fallback = (Logical.Fallback) logical;
            int branchCount = fallback.branches().size();
            List<NodeDescription> branches = new ArrayList<NodeDescription>(branchCount);
            for (int i = 0; i < branchCount; i++) {
                branches.add(null);
            }
            for (int i = branchCount - 1; i >= 0; i--) {
                branches.set(i, pop(resultStack));
            }
            return new NodeDescription(path, optLabel, NodeDescriptor.Kind.FALLBACK,
                    Optional.empty(), branches, null, fallback.trigger().name(), null, null, null, null, null, null, null, false);
        } else if (logical instanceof Logical.Parallel) {
            Logical.Parallel parallel = (Logical.Parallel) logical;
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
            return new NodeDescription(path, optLabel, NodeDescriptor.Kind.PARALLEL,
                    Optional.empty(), children, null, null, null, null, branches, null, null, null, null, false);
        } else if (logical instanceof Logical.Await) {
            Logical.Await await = (Logical.Await) logical;
            return new NodeDescription(path, optLabel, NodeDescriptor.Kind.AWAIT,
                    Optional.empty(), null, null, null, null, null, null, await.point().name(), null, null, null, false);
        } else if (logical instanceof Logical.Control) {
            Logical.Control control = (Logical.Control) logical;
            Optional<BindingDescriptor> binding = control.binding() == null
                    ? Optional.empty() : Optional.of(describeBinding(control.binding()));
            NodeDescription body = pop(resultStack);
            List<NodeDescription> children = Collections.singletonList(body);
            return new NodeDescription(path, optLabel, NodeDescriptor.Kind.CONTROL,
                    binding, children, null, null, null, null, null, null, control.kind().name(),
                    control.configuration(), null, false);
        } else if (logical instanceof Logical.Complete) {
            Logical.Complete complete = (Logical.Complete) logical;
            return new NodeDescription(path, optLabel, NodeDescriptor.Kind.COMPLETE,
                    Optional.empty(), null, null, null, null, null, null, null, null, null,
                    complete.outcome(), complete.identity());
        }
        throw new IllegalStateException("Unknown logical node: " + logical.getClass());
    }

    private static BindingDescriptor describeBinding(Logical.Binding binding) {
        if (binding == null) return null;
        Class<?> contract = binding.contract();
        Class<?> impl = binding.instance() != null ? binding.instance().getClass() : null;
        return new BindingDescriptor(contract, impl, binding.qualifier(), binding.kind().name());
    }
}
