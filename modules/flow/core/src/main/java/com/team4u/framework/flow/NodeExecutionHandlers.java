package com.team4u.framework.flow;

import java.util.concurrent.CancellationException;

/**
 * 运行时物理节点推进执行策略实现族。
 *
 * @author jay.wu
 */
final class NodeExecutionHandlers {
    private NodeExecutionHandlers() { }

    static final class InvokeExecutionHandler implements NodeExecutionHandler<PlanNode.Invoke> {
        @Override
        public Class<? extends PlanNode> key() {
            return PlanNode.Invoke.class;
        }

        @Override
        public MachineResult execute(PlanNode.Invoke invoke, RuntimeFrame frame, SerialMachine machine) {
            Outcome<?> outcome;
            try {
                outcome = machine.invocations().invoke(invoke, frame.entry, machine.deadline());
            } catch (CancellationException cancelled) {
                if (machine.cancellation().isCancelled()) {
                    machine.cancel();
                    return machine.result();
                }
                outcome = Outcome.failed(Failure.of(
                        "OPERATION_CANCELLED", "Operation was cancelled"));
            }
            machine.finish(outcome);
            return null;
        }
    }

    static final class SequenceExecutionHandler implements NodeExecutionHandler<PlanNode.Sequence> {
        @Override
        public Class<? extends PlanNode> key() {
            return PlanNode.Sequence.class;
        }

        @Override
        public MachineResult execute(PlanNode.Sequence sequence, RuntimeFrame frame, SerialMachine machine) {
            FrameEntrant.sequence(machine, frame, sequence);
            return null;
        }
    }

    static final class RouteExecutionHandler implements NodeExecutionHandler<PlanNode.Route> {
        @Override
        public Class<? extends PlanNode> key() {
            return PlanNode.Route.class;
        }

        @Override
        public MachineResult execute(PlanNode.Route route, RuntimeFrame frame, SerialMachine machine) {
            FrameEntrant.route(machine, frame, route);
            return null;
        }
    }

    static final class FallbackExecutionHandler implements NodeExecutionHandler<PlanNode.Fallback> {
        @Override
        public Class<? extends PlanNode> key() {
            return PlanNode.Fallback.class;
        }

        @Override
        public MachineResult execute(PlanNode.Fallback fallback, RuntimeFrame frame, SerialMachine machine) {
            FrameEntrant.fallback(machine, frame, fallback);
            return null;
        }
    }

    static final class ParallelExecutionHandler implements NodeExecutionHandler<PlanNode.Parallel> {
        @Override
        public Class<? extends PlanNode> key() {
            return PlanNode.Parallel.class;
        }

        @Override
        public MachineResult execute(PlanNode.Parallel parallel, RuntimeFrame frame, SerialMachine machine) {
            Outcome<?> outcome;
            try {
                outcome = new ParallelRunner(machine.flowId(), machine.flowVersion(), machine.state().executionId,
                        machine.cancellation(), machine.observer(), machine.executor()).run(parallel, frame.entry, machine.deadline());
            } catch (CancellationException cancelled) {
                machine.cancel();
                return machine.result();
            }
            machine.finish(outcome);
            return null;
        }
    }

    static final class AwaitExecutionHandler implements NodeExecutionHandler<PlanNode.Await> {
        @Override
        public Class<? extends PlanNode> key() {
            return PlanNode.Await.class;
        }

        @Override
        public MachineResult execute(PlanNode.Await await, RuntimeFrame frame, SerialMachine machine) {
            return FrameEntrant.await(machine, machine.state(), frame, await);
        }
    }

    static final class ControlExecutionHandler implements NodeExecutionHandler<PlanNode.Control> {
        @Override
        public Class<? extends PlanNode> key() {
            return PlanNode.Control.class;
        }

        @Override
        public MachineResult execute(PlanNode.Control control, RuntimeFrame frame, SerialMachine machine) {
            return ControlExecutor.enter(machine, frame, control);
        }
    }

    static final class CompleteExecutionHandler implements NodeExecutionHandler<PlanNode.Complete> {
        @Override
        public Class<? extends PlanNode> key() {
            return PlanNode.Complete.class;
        }

        @Override
        public MachineResult execute(PlanNode.Complete complete, RuntimeFrame frame, SerialMachine machine) {
            Outcome<?> outcome = complete.identity()
                    ? Outcome.accepted(frame.entry) : complete.outcome();
            machine.finish(outcome);
            return null;
        }
    }
}
