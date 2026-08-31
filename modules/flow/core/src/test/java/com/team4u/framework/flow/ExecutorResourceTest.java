package com.team4u.framework.flow;

import org.junit.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import com.team4u.framework.flow.api.Branch;
import com.team4u.framework.flow.api.FlowObserver;
import com.team4u.framework.flow.api.Gate;
import com.team4u.framework.flow.api.Operation;
import com.team4u.framework.flow.api.Policy;
import com.team4u.framework.flow.engine.SerialMachine;
import com.team4u.framework.flow.model.Cancellation;
import com.team4u.framework.flow.model.FlowResult;
import com.team4u.framework.flow.model.Outcome;
import com.team4u.framework.flow.spi.OperationResolver;

/**
 * 验证 Java 8 线程模型下，用户传入的 ExecutorService 用于 runAsync、超时回调与并行分支执行，
 * 明确分离 worker executor 与 runAsync dispatcher，解决 bounded executor 嵌套死锁，
 * 验证真 wait-all 退出保证、级联取消与外部中断保留。
 */
public class ExecutorResourceTest {

    @Test
    public void customExecutorExecutesAsyncAndParallelTasks() throws Exception {
        final AtomicInteger threadCount = new AtomicInteger();
        ExecutorService customWorker = Executors.newFixedThreadPool(4, new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "custom-flow-worker-" + threadCount.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        });

        try {
            final Set<String> executionThreadNames = Collections.synchronizedSet(new HashSet<String>());

            Branch<String, String> b1 = Branch.of("b1", (context, input) -> {
                executionThreadNames.add(Thread.currentThread().getName());
                return Outcome.accepted("b1:" + input);
            });
            Branch<String, String> b2 = Branch.of("b2", (context, input) -> {
                executionThreadNames.add(Thread.currentThread().getName());
                return Outcome.accepted("b2:" + input);
            });

            Flow<String, String> flow = Flow.parallel(b1, b2).join(results ->
                    results.allAccepted().map(v -> v.get(b1) + "|" + v.get(b2)));

            LocalExecutable<String, String> executable = Local.compile(flow, customWorker);
            CompletableFuture<FlowResult<String>> future = executable.runAsync("hello")
                    .toCompletableFuture();

            FlowResult<String> result = future.get(3, TimeUnit.SECONDS);
            assertEquals("b1:hello|b2:hello", result.requireAccepted());

            for (String threadName : executionThreadNames) {
                assertTrue("Expected thread name starting with custom-flow-worker-, but was " + threadName,
                        threadName.startsWith("custom-flow-worker-"));
            }
        } finally {
            customWorker.shutdown();
            customWorker.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    @Test
    public void customExecutorTimeoutExecutionAndCancellation() throws Exception {
        final AtomicInteger threadCount = new AtomicInteger();
        ExecutorService customWorker = Executors.newFixedThreadPool(2, new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                return new Thread(r, "timeout-worker-" + threadCount.incrementAndGet());
            }
        });

        try {
            Flow<String, String> flow = Flow.step(
                    (Operation<String, String>) (context, input) -> {
                Thread.sleep(80);
                return Outcome.accepted(input);
            }).timeout(Duration.ofMillis(30));

            LocalExecutable<String, String> executable = Local.compile(flow, customWorker);
            FlowResult<String> result = executable.run("test");
            assertTrue(result instanceof FlowResult.Completed);
            FlowResult.Completed<String> completed = (FlowResult.Completed<String>) result;
            assertTrue(completed.outcome() instanceof Outcome.Failed);
            assertEquals("TIMEOUT", ((Outcome.Failed<String>) completed.outcome()).failure().code());

            // Test async with cancellation
            Cancellation cancellation = Cancellation.create();
            CompletableFuture<FlowResult<String>> asyncResult = executable.runAsync("test", cancellation)
                    .toCompletableFuture();
            Thread.sleep(10);
            cancellation.cancel();
            assertTrue(asyncResult.get(2, TimeUnit.SECONDS) instanceof FlowResult.Cancelled);
        } finally {
            customWorker.shutdown();
            customWorker.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    @Test(timeout = 5000)
    public void singleThreadWorkerSyncRunCompletesTimeoutAndParallel() throws Exception {
        ExecutorService singleWorker = Executors.newSingleThreadExecutor();
        try {
            Branch<String, String> b1 = Branch.of("b1", (c, i) -> Outcome.accepted("1:" + i));
            Branch<String, String> b2 = Branch.of("b2", (c, i) -> Outcome.accepted("2:" + i));
            Flow<String, String> parallelFlow = Flow.parallel(b1, b2)
                    .join(r -> r.allAccepted().map(v -> v.get(b1) + "&" + v.get(b2)));

            LocalExecutable<String, String> exec = Local.compile(parallelFlow, singleWorker);
            // 同步调用在 caller 线程推进 SerialMachine，singleWorker 顺序执行 b1, b2
            FlowResult<String> result = exec.run("data");
            assertEquals("1:data&2:data", result.requireAccepted());

            // 测试带 timeout 的 step 在单线程 worker 下同步执行
            Flow<String, String> timedFlow = Flow.step((Operation<String, String>) (c, i) -> Outcome.accepted("ok:" + i))
                    .timeout(Duration.ofSeconds(2));
            LocalExecutable<String, String> timedExec = Local.compile(timedFlow, singleWorker);
            assertEquals("ok:data", timedExec.run("data").requireAccepted());
        } finally {
            singleWorker.shutdown();
            singleWorker.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    @Test(timeout = 5000)
    public void singleThreadWorkerAsyncRunOnDefaultDispatcherCompletes() throws Exception {
        ExecutorService singleWorker = Executors.newSingleThreadExecutor();
        try {
            Branch<String, String> b1 = Branch.of("b1", (c, i) -> Outcome.accepted("b1"));
            Branch<String, String> b2 = Branch.of("b2", (c, i) -> Outcome.accepted("b2"));
            Flow<String, String> parallelFlow = Flow.parallel(b1, b2)
                    .join(r -> r.allAccepted().map(v -> "joined"));

            LocalExecutable<String, String> exec = Local.compile(parallelFlow, singleWorker);
            // 默认 dispatcher (commonPool) 调度顶层，singleWorker 运行分支
            FlowResult<String> result = exec.runAsync("in").toCompletableFuture().get(4, TimeUnit.SECONDS);
            assertEquals("joined", result.requireAccepted());
        } finally {
            singleWorker.shutdown();
            singleWorker.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    @Test(timeout = 5000)
    public void dangerousSameSingleThreadExecutorFailsFast() throws Exception {
        ExecutorService single = Executors.newSingleThreadExecutor();
        try {
            Branch<String, String> b1 = Branch.of("b1", (c, i) -> Outcome.accepted("b1"));
            Flow<String, String> flow = Flow.parallel(b1).join(r -> r.allAccepted().map(v -> "ok"));
            LocalExecutable<String, String> exec = Local.compile(flow, single);

            try {
                // 危险组合：dispatcher == workerExecutor == single
                exec.runAsync("in", single);
                fail("Expected IllegalArgumentException for dangerous executor combination");
            } catch (IllegalArgumentException e) {
                assertTrue(e.getMessage().contains("Dangerous executor configuration"));
            }
        } finally {
            single.shutdown();
            single.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    @Test(timeout = 5000)
    public void parallelCancellationTrueWaitAll() throws Exception {
        ExecutorService workerPool = Executors.newFixedThreadPool(4);
        try {
            final CountDownLatch b2Started = new CountDownLatch(1);
            final AtomicBoolean b2FinallyRan = new AtomicBoolean(false);
            final AtomicLong b2ExitTimestamp = new AtomicLong(0);

            Branch<String, String> b1 = Branch.of("b1", (c, i) -> {
                b2Started.await();
                throw new RuntimeException("b1-boom");
            });

            Branch<String, String> b2 = Branch.of("b2", (c, i) -> {
                b2Started.countDown();
                try {
                    Thread.sleep(80);
                } finally {
                    b2FinallyRan.set(true);
                    b2ExitTimestamp.set(System.currentTimeMillis());
                }
                return Outcome.accepted("b2");
            });

            Flow<String, String> flow = Flow.parallel(b1, b2).join(r -> r.allAccepted().map(v -> "ok"));
            LocalExecutable<String, String> exec = Local.compile(flow, workerPool);

            FlowResult<String> result = exec.run("in");
            long runReturnTimestamp = System.currentTimeMillis();

            assertTrue("b2 finally must have executed before run returned", b2FinallyRan.get());
            assertTrue("b2 exit timestamp must be <= run return timestamp",
                    b2ExitTimestamp.get() <= runReturnTimestamp + 10);
            assertTrue(result instanceof FlowResult.Completed);
            assertTrue(((FlowResult.Completed<String>) result).outcome() instanceof Outcome.Failed);
        } finally {
            workerPool.shutdown();
            workerPool.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    @Test(timeout = 5000)
    public void cascadeCancellationInterruptsSubBranches() throws Exception {
        ExecutorService workerPool = Executors.newFixedThreadPool(2);
        try {
            final CountDownLatch branchEntered = new CountDownLatch(1);
            final AtomicBoolean branchInterrupted = new AtomicBoolean(false);

            Branch<String, String> b1 = Branch.of("b1", (context, input) -> {
                branchEntered.countDown();
                try {
                    // 有限分片等待：取消级联会在等待期间中断本线程
                    Thread.sleep(80);
                } catch (InterruptedException e) {
                    branchInterrupted.set(true);
                    Thread.currentThread().interrupt();
                }
                return Outcome.accepted("b1");
            });

            Flow<String, String> flow = Flow.parallel(b1).join(r -> r.allAccepted().map(v -> "ok"));
            LocalExecutable<String, String> exec = Local.compile(flow, workerPool);

            Cancellation rootCancellation = Cancellation.create();
            CompletableFuture<FlowResult<String>> future = exec.runAsync("in", rootCancellation)
                    .toCompletableFuture();

            assertTrue(branchEntered.await(2, TimeUnit.SECONDS));
            rootCancellation.cancel();

            FlowResult<String> result = future.get(2, TimeUnit.SECONDS);
            assertTrue(result instanceof FlowResult.Cancelled);
            assertTrue("Sub-branch thread should have received interruption upon parent cancellation",
                    branchInterrupted.get());
        } finally {
            workerPool.shutdown();
            workerPool.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    @Test(timeout = 5000)
    public void externalThreadInterruptPreserved() throws Exception {
        // 构造一个会执行并保持运行的 flow
        Flow<String, String> flow = Flow.step((Operation<String, String>) (c, i) -> {
            Thread.currentThread().interrupt(); // 外部/模拟中断
            return Outcome.accepted("done");
        });

        LocalExecutable<String, String> exec = Local.compile(flow);
        FlowResult<String> result = exec.run("in");

        // 调用方线程上的中断标志在 run 返回后必须得以保留
        assertTrue("Thread interrupt flag should remain true when not caused by flow cancellation",
                Thread.currentThread().isInterrupted());
        // 清理中断标志以便后续测试
        Thread.interrupted();
        assertEquals("done", result.requireAccepted());
    }

    @Test(timeout = 5000)
    public void singleWorkerParallelTimeoutCancelsQueuedTasksWithoutDeadlock() throws Exception {
        ExecutorService singleWorker = Executors.newSingleThreadExecutor();
        try {
            Branch<String, String> b1 = Branch.of("b1", (c, i) -> {
                Thread.sleep(80);
                return Outcome.accepted("b1");
            });
            Branch<String, String> b2 = Branch.of("b2", (c, i) -> Outcome.accepted("b2"));
            Branch<String, String> b3 = Branch.of("b3", (c, i) -> Outcome.accepted("b3"));

            Flow<String, String> flow = Flow.parallel(b1, b2, b3)
                    .join(r -> r.allAccepted().map(v -> "all"))
                    .timeout(Duration.ofMillis(25));

            LocalExecutable<String, String> exec = Local.compile(flow, singleWorker);

            Cancellation root = Cancellation.create();
            long start = System.currentTimeMillis();
            FlowResult<String> result = exec.run("in", root);
            long elapsed = System.currentTimeMillis() - start;

            assertTrue(result instanceof FlowResult.Completed);
            Outcome<?> outcome = ((FlowResult.Completed<String>) result).outcome();
            assertTrue(outcome instanceof Outcome.Failed);
            assertEquals("TIMEOUT", ((Outcome.Failed<?>) outcome).failure().code());
            assertTrue("Timeout should return promptly without infinite wait: " + elapsed + "ms", elapsed < 4000);
            assertEquals("All child tasks must be unlinked from root cancellation on timeout", 0, root.childCount());
        } finally {
            singleWorker.shutdownNow();
        }
    }

    @Test(timeout = 5000)
    public void controlledRejectingExecutorCleansUpStartedTasksAndReportsEvents() throws Exception {
        final AtomicInteger submissionCount = new AtomicInteger(0);
        final CountDownLatch b1Started = new CountDownLatch(1);
        final AtomicBoolean b1FinallyRan = new AtomicBoolean(false);
        final AtomicLong b1ExitTime = new AtomicLong(0);

        ExecutorService rejectingWorker = new java.util.concurrent.AbstractExecutorService() {
            private final ExecutorService underlying = Executors.newFixedThreadPool(1);

            @Override
            public void shutdown() { underlying.shutdown(); }
            @Override
            public List<Runnable> shutdownNow() { return underlying.shutdownNow(); }
            @Override
            public boolean isShutdown() { return underlying.isShutdown(); }
            @Override
            public boolean isTerminated() { return underlying.isTerminated(); }
            @Override
            public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
                return underlying.awaitTermination(timeout, unit);
            }
            @Override
            public void execute(Runnable command) {
                if (submissionCount.incrementAndGet() > 1) {
                    try {
                        b1Started.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException ignored) { }
                    throw new java.util.concurrent.RejectedExecutionException("Simulated queue saturation");
                }
                underlying.execute(command);
            }
        };

        try {
            Branch<String, String> b1 = Branch.of("b1", (c, i) -> {
                b1Started.countDown();
                try {
                    Thread.sleep(80);
                } finally {
                    b1FinallyRan.set(true);
                    b1ExitTime.set(System.currentTimeMillis());
                }
                return Outcome.accepted("b1");
            });
            Branch<String, String> b2 = Branch.of("b2", (c, i) -> Outcome.accepted("b2"));
            Branch<String, String> b3 = Branch.of("b3", (c, i) -> Outcome.accepted("b3"));

            Flow<String, String> flow = Flow.parallel(b1, b2, b3)
                    .join(r -> r.allAccepted().map(v -> "joined"));

            final List<FlowObserver.Event> events = Collections.synchronizedList(new ArrayList<FlowObserver.Event>());
            LocalExecutable<String, String> exec = Local.compile(flow, OperationResolver.rejecting(), events::add, rejectingWorker);

            Cancellation root = Cancellation.create();
            FlowResult<String> result = exec.run("in", root);
            long returnTime = System.currentTimeMillis();

            assertTrue("Started branch finally must execute before run returns", b1FinallyRan.get());
            assertTrue("Exit timestamp must precede run return", b1ExitTime.get() <= returnTime + 10);
            assertTrue(result instanceof FlowResult.Completed);
            Outcome<?> outcome = ((FlowResult.Completed<String>) result).outcome();
            assertTrue(outcome instanceof Outcome.Failed);
            assertEquals("EXECUTOR_REJECTED", ((Outcome.Failed<?>) outcome).failure().code());

            List<String> completedBranches = new ArrayList<String>();
            for (FlowObserver.Event event : events) {
                if (event.type() == FlowObserver.Type.PARALLEL_BRANCH_COMPLETED) {
                    assertEquals("FAILED", event.attributes().get("outcome"));
                    assertEquals("EXECUTOR_REJECTED", event.attributes().get("code"));
                    completedBranches.add(event.attributes().get("branch"));
                }
            }
            // 断言 3 个声明分支全部且按顺序完成报告
            assertEquals(3, completedBranches.size());
            assertEquals("b1", completedBranches.get(0));
            assertEquals("b2", completedBranches.get(1));
            assertEquals("b3", completedBranches.get(2));
            assertEquals("All child tasks must be unlinked from root cancellation on rejection", 0, root.childCount());
        } finally {
            rejectingWorker.shutdownNow();
        }
    }

    @Test(timeout = 5000)
    public void rejectingExecutorOnTimedOperationReturnsFailedOutcomeAndUnlinksChildToken() {
        ExecutorService alwaysRejecting = new java.util.concurrent.AbstractExecutorService() {
            @Override public void shutdown() { }
            @Override public List<Runnable> shutdownNow() { return Collections.emptyList(); }
            @Override public boolean isShutdown() { return false; }
            @Override public boolean isTerminated() { return false; }
            @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
            @Override public void execute(Runnable command) {
                throw new java.util.concurrent.RejectedExecutionException("Task rejected by test policy");
            }
        };

        Flow<String, String> flow = Flow.<String, String>step((c, i) -> Outcome.accepted(i))
                .timeout(Duration.ofSeconds(2));

        Cancellation root = Cancellation.create();
        LocalExecutable<String, String> exec = Local.compile(flow, alwaysRejecting);

        FlowResult<String> result = exec.run("in", root);
        assertTrue(result instanceof FlowResult.Completed);
        Outcome<?> outcome = ((FlowResult.Completed<String>) result).outcome();
        assertTrue(outcome instanceof Outcome.Failed);
        assertEquals("EXECUTOR_REJECTED", ((Outcome.Failed<?>) outcome).failure().code());
        assertEquals("Linked child tokens must be cleanly unlinked after rejection", 0, root.childCount());
    }

    @Test(timeout = 5000)
    public void rejectingExecutorOnTimedPolicyCallbackReturnsFailedOutcomeAndUnlinksChildToken() {
        ExecutorService alwaysRejecting = new java.util.concurrent.AbstractExecutorService() {
            @Override public void shutdown() { }
            @Override public List<Runnable> shutdownNow() { return Collections.emptyList(); }
            @Override public boolean isShutdown() { return false; }
            @Override public boolean isTerminated() { return false; }
            @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
            @Override public void execute(Runnable command) {
                throw new java.util.concurrent.RejectedExecutionException("Policy callback rejected");
            }
        };

        Policy<String> policy = (context, key) -> Gate.proceed();

        Flow<String, String> flow = Flow.<String, String>step((c, i) -> Outcome.accepted(i))
                .policy(policy, i -> i)
                .timeout(Duration.ofSeconds(2));

        Cancellation root = Cancellation.create();
        LocalExecutable<String, String> exec = Local.compile(flow, alwaysRejecting);

        FlowResult<String> result = exec.run("in", root);
        assertTrue(result instanceof FlowResult.Completed);
        Outcome<?> outcome = ((FlowResult.Completed<String>) result).outcome();
        assertTrue(outcome instanceof Outcome.Failed);
        assertEquals("POLICY_EXCEPTION", ((Outcome.Failed<?>) outcome).failure().code());
        assertEquals("Linked child tokens must be cleanly unlinked after callback rejection", 0, root.childCount());
    }

    @Test(timeout = 5000)
    public void timedOperationCancellationInterruptsWorkerAndUnlinksChildTokens() throws Exception {
        final CountDownLatch opStarted = new CountDownLatch(1);
        final AtomicBoolean opInterrupted = new AtomicBoolean(false);

        Flow<String, String> flow = Flow.<String, String>step((context, input) -> {
            opStarted.countDown();
            try {
                // 有限分片等待：父取消会在等待期间中断本工作线程
                Thread.sleep(80);
            } catch (InterruptedException e) {
                opInterrupted.set(true);
                Thread.currentThread().interrupt();
            }
            return Outcome.accepted("done");
        }).timeout(Duration.ofSeconds(10));

        LocalExecutable<String, String> exec = Local.compile(flow);

        Cancellation cancellation = Cancellation.create();
        CompletableFuture<FlowResult<String>> future = exec.runAsync("in", cancellation).toCompletableFuture();

        assertTrue(opStarted.await(2, TimeUnit.SECONDS));
        cancellation.cancel();

        FlowResult<String> result = future.get(2, TimeUnit.SECONDS);
        assertTrue(result instanceof FlowResult.Cancelled);
        assertTrue("Worker thread running timed Operation should be interrupted on parent cancellation",
                opInterrupted.get());

        // 验证重复 timed 执行后，父 token 下没有残留子 token 强引用（childCount == 0）
        Cancellation root = Cancellation.create();
        Flow<String, String> quickFlow = Flow.<String, String>step((context, input) -> Outcome.accepted(input))
                .timeout(Duration.ofSeconds(2));
        LocalExecutable<String, String> quickExec = Local.compile(quickFlow);

        for (int i = 0; i < 5; i++) {
            assertEquals("test", quickExec.run("test", root).requireAccepted());
            assertEquals("Linked child tokens must be unlinked after execution", 0, root.childCount());
        }
    }

    @Test(timeout = 5000)
    public void nestedParallelAndBranchTimeoutFailFastOnNonForkJoinWorker() {
        ExecutorService singleWorker = Executors.newSingleThreadExecutor();
        try {
            Branch<String, String> inner1 = Branch.of("i1", (c, i) -> Outcome.accepted(i));
            Flow<String, String> innerParallel = Flow.parallel(inner1).join(r -> r.allAccepted().map(v -> "inner"));

            Branch<String, String> outer1 = Branch.of("o1", innerParallel);
            Flow<String, String> nestedParallelFlow = Flow.parallel(outer1).join(r -> r.allAccepted().map(v -> "outer"));

            try {
                Local.compile(nestedParallelFlow, singleWorker);
                fail("Expected IllegalArgumentException for nested parallel on non-ForkJoin worker");
            } catch (IllegalArgumentException e) {
                assertTrue(e.getMessage().contains("ForkJoinPool"));
            }

            // Parallel branch with timeout control
            Branch<String, String> timedBranch = Branch.of("tb",
                    Flow.<String, String>step((c, i) -> Outcome.accepted(i)).timeout(Duration.ofSeconds(1)));
            Flow<String, String> parallelWithTimedBranch = Flow.parallel(timedBranch).join(r -> r.allAccepted().map(v -> "ok"));

            try {
                Local.compile(parallelWithTimedBranch, singleWorker);
                fail("Expected IllegalArgumentException for parallel branch with timeout on non-ForkJoin worker");
            } catch (IllegalArgumentException e) {
                assertTrue(e.getMessage().contains("ForkJoinPool"));
            }

            // Top-level timeout wrapping parallel should NOT fail fast
            Branch<String, String> normalB1 = Branch.of("nb1", (c, i) -> Outcome.accepted("ok"));
            Flow<String, String> topTimedParallel = Flow.parallel(normalB1).join(r -> r.allAccepted().map(v -> "ok"))
                    .timeout(Duration.ofSeconds(5));
            LocalExecutable<String, String> exec = Local.compile(topTimedParallel, singleWorker);
            assertEquals("ok", exec.run("in").requireAccepted());

            // Nested parallel on commonPool (default) should compile and run successfully
            LocalExecutable<String, String> commonPoolExec = Local.compile(nestedParallelFlow);
            assertEquals("outer", commonPoolExec.run("in").requireAccepted());
        } finally {
            singleWorker.shutdownNow();
        }
    }
}
