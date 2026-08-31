package com.team4u.framework.flow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 逻辑 AST 降级编译策略实现族。
 *
 * @author jay.wu
 */
final class LogicalLowerers {
    private LogicalLowerers() { }

    static final class InvokeLowerer implements LogicalLowerer<Logical.Invoke> {
        @Override
        public Class<? extends Logical> key() {
            return Logical.Invoke.class;
        }

        @Override
        public List<Compiler.Child> children(Logical.Invoke logical, Compiler.Work work) {
            return Collections.emptyList();
        }

        @Override
        public PlanNode build(Logical.Invoke logical, Compiler.Work work, LoweringContext context) {
            return context.invoke(logical, work.path(), work.label());
        }
    }

    static final class SequenceLowerer implements LogicalLowerer<Logical.Sequence> {
        @Override
        public Class<? extends Logical> key() {
            return Logical.Sequence.class;
        }

        @Override
        public List<Compiler.Child> children(Logical.Sequence sequence, Compiler.Work work) {
            List<Compiler.Child> children = new ArrayList<Compiler.Child>(sequence.children().size());
            for (int index = 0; index < sequence.children().size(); index++) {
                Logical child = sequence.children().get(index);
                children.add(new Compiler.Child(child, work.path() + "/" + index, work.parallel()));
            }
            return children;
        }

        @Override
        public PlanNode build(Logical.Sequence sequence, Compiler.Work work, LoweringContext context) {
            if (sequence.scopeName() != null && !context.scopeNames().add(sequence.scopeName())) {
                context.problem("DUPLICATE_SCOPE", work.path(), "Duplicate scope: " + sequence.scopeName());
            }
            List<PlanNode> children = new ArrayList<PlanNode>(sequence.children().size());
            for (int index = 0; index < sequence.children().size(); index++) {
                children.add(context.required(work.path() + "/" + index));
            }
            return new PlanNode.Sequence(Compiler.descriptor(work, NodeDescriptor.Kind.SEQUENCE),
                    children, sequence.scopeName());
        }
    }

    static final class RouteLowerer implements LogicalLowerer<Logical.Route> {
        @Override
        public Class<? extends Logical> key() {
            return Logical.Route.class;
        }

        @Override
        public List<Compiler.Child> children(Logical.Route route, Compiler.Work work) {
            List<Compiler.Child> children = new ArrayList<Compiler.Child>(route.cases().size() + 1);
            for (int index = 0; index < route.cases().size(); index++) {
                children.add(new Compiler.Child(route.cases().get(index).branch(),
                        work.path() + "/case:" + index, work.parallel()));
            }
            if (route.otherwise() != null) {
                children.add(new Compiler.Child(route.otherwise(),
                        work.path() + "/otherwise", work.parallel()));
            }
            return children;
        }

        @Override
        public PlanNode build(Logical.Route route, Compiler.Work work, LoweringContext context) {
            String selectorPath = work.path() + "/selector";
            PlanNode.Invoke selector = context.invoke(new Logical.Invoke(route.selector(),
                    value -> value, (ignored, value) -> value), selectorPath, null);
            context.byPath().put(selectorPath, selector);
            List<PlanNode.Route.RouteCase> cases = new ArrayList<PlanNode.Route.RouteCase>(route.cases().size());
            for (int index = 0; index < route.cases().size(); index++) {
                cases.add(new PlanNode.Route.RouteCase(route.cases().get(index).key(),
                        context.required(work.path() + "/case:" + index)));
            }
            PlanNode otherwise = route.otherwise() == null ? null
                    : context.required(work.path() + "/otherwise");
            return new PlanNode.Route(Compiler.descriptor(work, NodeDescriptor.Kind.ROUTE),
                    selector, cases, otherwise);
        }
    }

    static final class FallbackLowerer implements LogicalLowerer<Logical.Fallback> {
        @Override
        public Class<? extends Logical> key() {
            return Logical.Fallback.class;
        }

        @Override
        public List<Compiler.Child> children(Logical.Fallback fallback, Compiler.Work work) {
            List<Compiler.Child> children = new ArrayList<Compiler.Child>(fallback.branches().size());
            for (int index = 0; index < fallback.branches().size(); index++) {
                children.add(new Compiler.Child(fallback.branches().get(index),
                        work.path() + "/branch:" + index, work.parallel()));
            }
            return children;
        }

        @Override
        public PlanNode build(Logical.Fallback fallback, Compiler.Work work, LoweringContext context) {
            List<PlanNode> branches = new ArrayList<PlanNode>(fallback.branches().size());
            for (int index = 0; index < fallback.branches().size(); index++) {
                branches.add(context.required(work.path() + "/branch:" + index));
            }
            PlanNode.Fallback.Trigger trigger = fallback.trigger() == Logical.Fallback.Trigger.SKIPPED
                    ? PlanNode.Fallback.Trigger.SKIPPED : PlanNode.Fallback.Trigger.FAILED;
            return new PlanNode.Fallback(Compiler.descriptor(work, NodeDescriptor.Kind.FALLBACK),
                    trigger, branches);
        }
    }

    static final class ParallelLowerer implements LogicalLowerer<Logical.Parallel> {
        @Override
        public Class<? extends Logical> key() {
            return Logical.Parallel.class;
        }

        @Override
        public List<Compiler.Child> children(Logical.Parallel parallel, Compiler.Work work) {
            List<Compiler.Child> children = new ArrayList<Compiler.Child>(parallel.branches().size());
            for (int index = 0; index < parallel.branches().size(); index++) {
                children.add(new Compiler.Child(parallel.branches().get(index).flow(),
                        work.path() + "/branch:" + index, true));
            }
            return children;
        }

        @Override
        public PlanNode build(Logical.Parallel parallel, Compiler.Work work, LoweringContext context) {
            List<PlanNode.ParallelBranch> branches = new ArrayList<PlanNode.ParallelBranch>(parallel.branches().size());
            for (int index = 0; index < parallel.branches().size(); index++) {
                Branch<?, ?> token = parallel.branches().get(index).token();
                if (!context.branchNames().add(token.name())) {
                    context.problem("DUPLICATE_BRANCH", work.path(), "Duplicate branch: " + token.name());
                }
                branches.add(new PlanNode.ParallelBranch(token,
                        context.required(work.path() + "/branch:" + index)));
            }
            return new PlanNode.Parallel(Compiler.descriptor(work, NodeDescriptor.Kind.PARALLEL),
                    branches, parallel.join());
        }
    }

    static final class AwaitLowerer implements LogicalLowerer<Logical.Await> {
        @Override
        public Class<? extends Logical> key() {
            return Logical.Await.class;
        }

        @Override
        public List<Compiler.Child> children(Logical.Await await, Compiler.Work work) {
            return Collections.emptyList();
        }

        @Override
        public PlanNode build(Logical.Await await, Compiler.Work work, LoweringContext context) {
            if (work.parallel()) {
                context.problem("PARALLEL_AWAIT", work.path(), "Parallel branches cannot await");
            }
            ResumePoint<?> existing = context.resumePoints().putIfAbsent(await.point().name(), await.point());
            if (existing != null) {
                context.problem("DUPLICATE_RESUME_POINT", work.path(),
                        "Duplicate ResumePoint name " + await.point().name());
            }
            return new PlanNode.Await(Compiler.descriptor(work, NodeDescriptor.Kind.AWAIT), await.point());
        }
    }

    static final class ControlLowerer implements LogicalLowerer<Logical.Control> {
        @Override
        public Class<? extends Logical> key() {
            return Logical.Control.class;
        }

        @Override
        public List<Compiler.Child> children(Logical.Control control, Compiler.Work work) {
            return Collections.singletonList(new Compiler.Child(control.body(), work.path() + "/body", work.parallel()));
        }

        @Override
        public PlanNode build(Logical.Control control, Compiler.Work work, LoweringContext context) {
            if (work.parallel() && control.kind() == Logical.Control.Kind.PERSISTENT_POLICY) {
                context.problem("PARALLEL_PERSISTENT_POLICY", work.path(),
                        "Parallel branches cannot use PersistentPolicy");
            }
            PlanNode.BoundTarget target = control.binding() == null ? null
                    : context.resolve(control.binding(), work.path());
            PlanNode.Control.Kind kind = PlanNode.Control.Kind.valueOf(control.kind().name());
            return new PlanNode.Control(Compiler.descriptor(work, NodeDescriptor.Kind.CONTROL), kind,
                    context.required(work.path() + "/body"), target,
                    control.keyProjection(), control.configuration());
        }
    }

    static final class CompleteLowerer implements LogicalLowerer<Logical.Complete> {
        @Override
        public Class<? extends Logical> key() {
            return Logical.Complete.class;
        }

        @Override
        public List<Compiler.Child> children(Logical.Complete complete, Compiler.Work work) {
            return Collections.emptyList();
        }

        @Override
        public PlanNode build(Logical.Complete complete, Compiler.Work work, LoweringContext context) {
            return new PlanNode.Complete(Compiler.descriptor(work, NodeDescriptor.Kind.COMPLETE),
                    complete.outcome(), complete.identity());
        }
    }
}
