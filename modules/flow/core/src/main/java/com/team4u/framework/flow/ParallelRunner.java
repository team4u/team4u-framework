package com.team4u.framework.flow;

import lombok.Getter;
import lombok.experimental.Accessors;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 结构化并行（Parallel）多分支并发调度与汇聚执行器（Parallel Fork-Join Runner）。
 *
 * <p>核心机制与并发可靠性保证：
 * <ul>
 *   <li><b>多分支独立隔离</b>：每个并发分支运行在各自独立的 {@link SerialMachine} 栈中，互不干扰；</li>
 *   <li><b>非队头阻塞汇合</b>：通过内部任务就绪队列（{@link LinkedBlockingQueue}）驱动多路完成监听，结合 {@link ManagedBlockers} 支持 ForkJoinPool 补偿，避免公共池线程饥饿与死锁；</li>
 *   <li><b>True Wait-All 退出保证</b>：采用显式 3 状态机（{@code NOT_STARTED, RUNNING, EXITED}）的 {@code TrackedTask} 与 {@link CountDownLatch}，确保在任何正常返回、异常中断、超时或取消场景下，主线程均等待所有已提交的工作线程完全安全退出，彻底杜绝并发孤儿线程泄漏；</li>
 *   <li><b>声明顺序汇聚</b>：所有分支执行完成后，按原始声明顺序构造 {@link ParallelResults} 交付给 {@link JoinStrategy#join} 聚合。</li>
 * </ul>
 * </p>
 *
 * @author jay.wu
 */
final class ParallelRunner {

    @Getter
    @Accessors(fluent = true)
    private static final class Task {
        private final PlanNode.ParallelBranch branch;
        private final Cancellation cancellation;
        private final CountDownLatch doneLatch = new CountDownLatch(1);
        private final AtomicReference<MachineResult> resultRef = new AtomicReference<MachineResult>();
        private final AtomicReference<Throwable> failureRef = new AtomicReference<Throwable>();
        private volatile Future<?> future;

        public Task(PlanNode.ParallelBranch branch, Cancellation cancellation) {
            this.branch = branch;
            this.cancellation = cancellation;
        }

        public void setFuture(Future<?> future) {
            this.future = future;
        }
    }

    /**
     * 具备 3 阶段（NOT_STARTED, RUNNING, EXITED）状态机的 TrackedTask。
     * 保证：
     * 1. 任务在启动前若被取消，done() 原子从 NOT_STARTED=>EXITED 并释放 latch 和进入 completionQueue；
     * 2. 任务若开始执行，原子从 NOT_STARTED=>RUNNING，latch 严格由 run() 最外层 finally 释放，
     *    防止运行中 cancel 导致的伪 wait-all；
     * 3. 启动前取消的任务后续被工作线程拉取执行时，run() 直接退出而不执行任何用户逻辑。
     */
    private static final class TrackedTask extends FutureTask<Void> {
        private static final int NOT_STARTED = 0;
        private static final int RUNNING = 1;
        private static final int EXITED = 2;

        private final Task task;
        private final BlockingQueue<Task> completionQueue;
        private final AtomicInteger phase = new AtomicInteger(NOT_STARTED);

        TrackedTask(Task task, BlockingQueue<Task> completionQueue, Runnable userCode) {
            super(userCode, null);
            this.task = task;
            this.completionQueue = completionQueue;
        }

        @Override
        public void run() {
            if (!phase.compareAndSet(NOT_STARTED, RUNNING)) {
                // 已在启动前被取消/标记 EXITED，不可执行用户逻辑
                return;
            }
            try {
                super.run();
            } finally {
                phase.set(EXITED);
                task.doneLatch().countDown();
                completionQueue.add(task);
            }
        }

        @Override
        protected void done() {
            if (phase.compareAndSet(NOT_STARTED, EXITED)) {
                task.doneLatch().countDown();
                completionQueue.add(task);
            }
        }
    }

    private final String flowId;
    private final int flowVersion;
    private final String executionId;
    private final Cancellation cancellation;
    private final FlowObserver observer;
    private final ExecutorService executor;

    ParallelRunner(String flowId, int flowVersion, String executionId,
                   Cancellation cancellation, FlowObserver observer, ExecutorService executor) {
        this.flowId = flowId;
        this.flowVersion = flowVersion;
        this.executionId = executionId;
        this.cancellation = cancellation;
        this.observer = observer;
        this.executor = executor;
    }

    /**
     * 执行所有分支并汇合。采用非队头阻塞的多路就绪监听与 true wait-all 退出保证。
     * 任一分支抛出 Error 会立即取消其余分支并抛出；取消与超时会向未完成分支传播并等待所有工作线程安全退出。
     */
    Outcome<?> run(final PlanNode.Parallel node, final Object input, Instant deadline) {
        Map<String, String> startAttrs = new LinkedHashMap<String, String>();
        startAttrs.put("branches", Integer.toString(node.branches().size()));
        event(FlowObserver.Type.PARALLEL_STARTED, node.descriptor(), startAttrs);

        final List<Task> tasks = new ArrayList<Task>();
        final BlockingQueue<Task> completionQueue = new LinkedBlockingQueue<Task>();
        final Set<PlanNode.ParallelBranch> reported =
                Collections.newSetFromMap(new IdentityHashMap<PlanNode.ParallelBranch, Boolean>());

        try {
            for (final PlanNode.ParallelBranch branch : node.branches()) {
                final Cancellation childCancellation = Cancellation.linked(cancellation);
                final Task task = new Task(branch, childCancellation);
                tasks.add(task);

                TrackedTask futureTask = new TrackedTask(task, completionQueue, new Runnable() {
                    @Override
                    public void run() {
                        try {
                            MachineState state = new MachineState(branch.plan(), executionId, input);
                            SerialMachine machine = new SerialMachine(branch.plan(), flowId, flowVersion,
                                    state, childCancellation, observer, executor);
                            task.resultRef().set(machine.drive());
                        } catch (Throwable throwable) {
                            task.failureRef().set(throwable);
                        }
                    }
                });

                task.setFuture(futureTask);
                executor.execute(futureTask);
            }
        } catch (Throwable submitError) {
            cancel(tasks);
            waitAllExited(tasks);
            unlink(tasks);
            if (submitError instanceof Error) {
                reportUnfinished(node, reported, "EXECUTOR_ERROR");
                throw (Error) submitError;
            }
            reportUnfinished(node, reported, "EXECUTOR_REJECTED");
            return Outcome.failed(Failure.of("EXECUTOR_REJECTED",
                    submitError.getMessage() == null ? "Task execution rejected" : submitError.getMessage()));
        }

        final Map<PlanNode.ParallelBranch, Outcome<?>> branchOutcomes =
                new IdentityHashMap<PlanNode.ParallelBranch, Outcome<?>>();

        Error fatal = null;
        int completedCount = 0;
        int totalBranches = tasks.size();

        while (completedCount < totalBranches) {
            Task completedTask;
            try {
                if (deadline == null) {
                    completedTask = ManagedBlockers.take(completionQueue);
                } else {
                    Duration remaining = Duration.between(Instant.now(), deadline);
                    if (remaining.isNegative() || remaining.isZero()) {
                        cancel(tasks);
                        waitAllExited(tasks);
                        unlink(tasks);
                        reportUnfinished(node, reported, "TIMEOUT");
                        return Outcome.failed(Failure.of("TIMEOUT", "Flow scope deadline elapsed"));
                    }
                    completedTask = ManagedBlockers.poll(completionQueue, remaining);
                    if (completedTask == null) {
                        // 超时未就绪
                        cancel(tasks);
                        waitAllExited(tasks);
                        unlink(tasks);
                        reportUnfinished(node, reported, "TIMEOUT");
                        return Outcome.failed(Failure.of("TIMEOUT", "Flow scope deadline elapsed"));
                    }
                }
            } catch (InterruptedException interrupted) {
                cancel(tasks);
                waitAllExited(tasks);
                unlink(tasks);
                if (cancellation.isCancelled()) {
                    reportUnfinished(node, reported, "CANCELLED");
                    Thread.interrupted();
                    throw new CancellationException("flow execution was cancelled");
                }
                reportUnfinished(node, reported, "PARALLEL_INTERRUPTED");
                Thread.currentThread().interrupt();
                return Outcome.failed(Failure.of("PARALLEL_INTERRUPTED", "Parallel wait was interrupted"));
            }

            completedCount++;
            Throwable failure = completedTask.failureRef().get();
            if (failure instanceof Error) {
                fatal = (Error) failure;
                cancel(tasks);
                waitAllExited(tasks);
                unlink(tasks);
                reportUnfinished(node, reported, "FATAL_ERROR");
                throw fatal;
            }

            Outcome<?> branchOutcome;
            if (failure != null) {
                branchOutcome = Outcome.failed(Failure.of("PARALLEL_EXCEPTION",
                        failure.getMessage() == null ? failure.toString() : failure.getMessage()));
            } else {
                MachineResult result = completedTask.resultRef().get();
                if (result == null || result.lifecycle() == MachineState.Lifecycle.CANCELLED) {
                    if (cancellation.isCancelled()) {
                        cancel(tasks);
                        waitAllExited(tasks);
                        unlink(tasks);
                        reportUnfinished(node, reported, "CANCELLED");
                        throw new CancellationException("flow execution was cancelled");
                    }
                    branchOutcome = Outcome.failed(Failure.of(
                            "PARALLEL_BRANCH_CANCELLED", "Parallel branch was cancelled"));
                } else {
                    branchOutcome = result.outcome();
                }
            }

            branchOutcomes.put(completedTask.branch(), branchOutcome);
            Map<String, String> branchAttrs = new LinkedHashMap<String, String>();
            branchAttrs.put("branch", completedTask.branch().token().name());
            branchAttrs.put("outcome", branchOutcome.kind().name());
            event(FlowObserver.Type.PARALLEL_BRANCH_COMPLETED, node.descriptor(), branchAttrs);
            reported.add(completedTask.branch());
        }

        // 确保所有任务均完全退出
        waitAllExited(tasks);
        unlink(tasks);

        if (cancellation.isCancelled()) {
            reportUnfinished(node, reported, "CANCELLED");
            throw new CancellationException("flow execution was cancelled");
        }

        // 按原始声明顺序收集分支结果
        final List<Branch<?, ?>> tokens = new ArrayList<Branch<?, ?>>();
        final List<Outcome<?>> orderedOutcomes = new ArrayList<Outcome<?>>();
        for (PlanNode.ParallelBranch pb : node.branches()) {
            tokens.add(pb.token());
            orderedOutcomes.add(branchOutcomes.get(pb));
        }

        CallbackRunner.Result<Outcome<?>> join = new CallbackRunner(cancellation, executor).call(
                ignored -> Objects.requireNonNull(node.join().join(
                        new ParallelResults(tokens, orderedOutcomes)),
                        "parallel join returned null"), deadline);

        if (cancellation.isCancelled()) {
            throw new CancellationException("flow execution was cancelled");
        }

        Outcome<?> joined;
        if (join.timeout()) {
            joined = Outcome.failed(Failure.of("TIMEOUT", "Flow scope deadline elapsed"));
        } else if (join.failure() != null) {
            if (cancellation.isCancelled()) {
                throw new CancellationException("flow execution was cancelled");
            }
            joined = Outcome.failed(Failure.of("JOIN_EXCEPTION", join.failure().toString()));
        } else {
            joined = join.value();
        }

        Map<String, String> joinAttrs = new LinkedHashMap<String, String>();
        joinAttrs.put("outcome", joined.kind().name());
        event(FlowObserver.Type.PARALLEL_JOINED, node.descriptor(), joinAttrs);
        return joined;
    }

    private void reportUnfinished(PlanNode.Parallel node,
                                  Set<PlanNode.ParallelBranch> reported, String code) {
        for (PlanNode.ParallelBranch branch : node.branches()) {
            if (reported.contains(branch)) continue;
            reported.add(branch);
            Map<String, String> attrs = new LinkedHashMap<String, String>();
            attrs.put("branch", branch.token().name());
            attrs.put("outcome", Outcome.Kind.FAILED.name());
            attrs.put("code", code);
            event(FlowObserver.Type.PARALLEL_BRANCH_COMPLETED, node.descriptor(), attrs);
        }
    }

    private static void cancel(List<Task> tasks) {
        for (Task task : tasks) {
            task.cancellation().cancel();
            Future<?> future = task.future();
            if (future != null) {
                future.cancel(true);
            }
        }
    }

    private static void unlink(List<Task> tasks) {
        for (Task task : tasks) {
            task.cancellation().unlink();
        }
    }

    /**
     * 真正阻塞等待所有已启动分支的工作线程完全退出。
     * 在任何情况下都不允许虚假返回。
     */
    private static void waitAllExited(List<Task> tasks) {
        boolean interrupted = false;
        for (Task task : tasks) {
            while (task.doneLatch().getCount() > 0) {
                try {
                    ManagedBlockers.await(task.doneLatch());
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void event(FlowObserver.Type type, NodeDescriptor descriptor,
                       Map<String, String> attributes) {
        Metadata metadata = new Metadata(flowId, flowVersion, executionId,
                descriptor.path(), descriptor.label());
        try {
            observer.onEvent(new FlowObserver.Event(type, Instant.now(), metadata,
                    descriptor, attributes));
        } catch (RuntimeException ignored) {
            // Observers cannot alter execution.
        }
    }
}
