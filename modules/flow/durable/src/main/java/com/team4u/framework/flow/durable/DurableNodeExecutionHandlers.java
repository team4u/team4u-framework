package com.team4u.framework.flow.durable;

/**
 * Durable 物理节点进栈推进执行策略实现族。
 *
 * @author jay.wu
 */
final class DurableNodeExecutionHandlers {
    private DurableNodeExecutionHandlers() { }

    static final class InvokeExecutionHandler implements DurableNodeExecutionHandler<DurablePlanNode.Invoke> {
        @Override
        public Class<? extends DurablePlanNode> key() {
            return DurablePlanNode.Invoke.class;
        }

        @Override
        public void execute(DurablePlanNode.Invoke invoke, DurableState.RuntimeFrame frame, DurableMachine machine) {
            machine.invoke(frame, invoke);
        }
    }

    static final class SequenceExecutionHandler implements DurableNodeExecutionHandler<DurablePlanNode.Sequence> {
        @Override
        public Class<? extends DurablePlanNode> key() {
            return DurablePlanNode.Sequence.class;
        }

        @Override
        public void execute(DurablePlanNode.Sequence sequence, DurableState.RuntimeFrame frame, DurableMachine machine) {
            machine.enterSequence(frame, sequence);
        }
    }

    static final class RouteExecutionHandler implements DurableNodeExecutionHandler<DurablePlanNode.Route> {
        @Override
        public Class<? extends DurablePlanNode> key() {
            return DurablePlanNode.Route.class;
        }

        @Override
        public void execute(DurablePlanNode.Route route, DurableState.RuntimeFrame frame, DurableMachine machine) {
            machine.enterRoute(frame, route);
        }
    }

    static final class FallbackExecutionHandler implements DurableNodeExecutionHandler<DurablePlanNode.Fallback> {
        @Override
        public Class<? extends DurablePlanNode> key() {
            return DurablePlanNode.Fallback.class;
        }

        @Override
        public void execute(DurablePlanNode.Fallback fallback, DurableState.RuntimeFrame frame, DurableMachine machine) {
            machine.enterFallback(frame, fallback);
        }
    }

    static final class ParallelExecutionHandler implements DurableNodeExecutionHandler<DurablePlanNode.Parallel> {
        @Override
        public Class<? extends DurablePlanNode> key() {
            return DurablePlanNode.Parallel.class;
        }

        @Override
        public void execute(DurablePlanNode.Parallel parallel, DurableState.RuntimeFrame frame, DurableMachine machine) {
            machine.runParallel(frame, parallel);
        }
    }

    static final class AwaitExecutionHandler implements DurableNodeExecutionHandler<DurablePlanNode.Await> {
        @Override
        public Class<? extends DurablePlanNode> key() {
            return DurablePlanNode.Await.class;
        }

        @Override
        public void execute(DurablePlanNode.Await await, DurableState.RuntimeFrame frame, DurableMachine machine) {
            machine.enterAwait(frame, await);
        }
    }

    static final class ControlExecutionHandler implements DurableNodeExecutionHandler<DurablePlanNode.Control> {
        @Override
        public Class<? extends DurablePlanNode> key() {
            return DurablePlanNode.Control.class;
        }

        @Override
        public void execute(DurablePlanNode.Control control, DurableState.RuntimeFrame frame, DurableMachine machine) {
            DurableControlKindHandler handler = DurableControlKindRegistry.global().get(control.kind())
                    .orElseThrow(() -> new IllegalStateException("Unknown control kind: " + control.kind()));
            handler.enter(control, frame, machine);
        }
    }

    static final class CompleteExecutionHandler implements DurableNodeExecutionHandler<DurablePlanNode.Complete> {
        @Override
        public Class<? extends DurablePlanNode> key() {
            return DurablePlanNode.Complete.class;
        }

        @Override
        public void execute(DurablePlanNode.Complete complete, DurableState.RuntimeFrame frame, DurableMachine machine) {
            machine.complete(frame, complete);
        }
    }
}
