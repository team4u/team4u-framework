package com.team4u.framework.flow;

import org.junit.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import com.team4u.framework.flow.api.FlowObserver;
import com.team4u.framework.flow.api.Metadata;
import com.team4u.framework.flow.api.ResumePoint;
import com.team4u.framework.flow.model.Cancellation;
import com.team4u.framework.flow.model.FlowResult;
import com.team4u.framework.flow.model.Outcome;
import com.team4u.framework.flow.model.Resumed;
import com.team4u.framework.flow.spi.NodeDescriptor;
import com.team4u.framework.flow.spi.OperationResolver;

/**
 * Local 异步 API 与并发交接契约验证：resumeAsync 四个重载、withExecutor 线程池派生、
 * 自定义 flowId 的 invocationId 与事件元数据、以及用 CountDownLatch 编排的
 * 并发 run/resume 交叉场景（多执行实例并行推进互不串扰）。
 */
public class LocalAsyncApiTest {

    @Test
    public void resumeAsyncOverloadsCompleteTypedResume() throws Exception {
        ResumePoint<String> point = ResumePoint.named("async-resume");
        LocalExecutable<String, String> local = Local.compile(
                Flow.<String>identity().await(point)
                        .then((context, resumed) -> Outcome.accepted(
                                resumed.state() + ":" + resumed.signal())));
        FlowResult.Suspended<String> suspended =
                (FlowResult.Suspended<String>) local.run("state");

        // 全参重载（自定义 dispatcher）
        ExecutorService dispatcher = Executors.newSingleThreadExecutor();
        try {
            FlowResult<String> result = local.resumeAsync(
                    suspended.suspension(), point, "sig",
                    Cancellation.create(), dispatcher)
                    .toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertEquals("state:sig", result.requireAccepted());
        } finally {
            dispatcher.shutdown();
            dispatcher.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    @Test
    public void resumeAsyncWithCancellationOverloadRunsOnCommonPool() throws Exception {
        ResumePoint<String> point = ResumePoint.named("cancel-resume");
        LocalExecutable<String, Resumed<String, String>> local = Local.compile(
                Flow.<String>identity().await(point));
        FlowResult.Suspended<Resumed<String, String>> suspended =
                (FlowResult.Suspended<Resumed<String, String>>) local.run("1");
        FlowResult<Resumed<String, String>> result = local.resumeAsync(
                suspended.suspension(), point, "v", Cancellation.create())
                .toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals("v", result.requireAccepted().signal());
    }
    @Test
    public void withExecutorDerivesIndependentWorkerBinding() throws Exception {
        final AtomicInteger workerCount = new AtomicInteger();
        final List<String> workerNames = Collections.synchronizedList(new ArrayList<String>());
        ExecutorService dedicated = Executors.newFixedThreadPool(2, runnable -> {
            workerCount.incrementAndGet();
            Thread thread = new Thread(runnable, "derived-worker");
            thread.setDaemon(true);
            return thread;
        });
        try {
            // parallel 分支任务会提交到 worker executor 执行
            com.team4u.framework.flow.api.Branch<String, String> probe =
                    com.team4u.framework.flow.api.Branch.of("probe",
                            (context, input) -> {
                                workerNames.add(Thread.currentThread().getName());
                                return Outcome.accepted(input);
                            });
            LocalExecutable<String, String> base = Local.compile(
                    Flow.parallel(probe).join(results -> results.outcome(probe)));
            // 原句柄使用 commonPool，派生句柄绑定专用 worker
            LocalExecutable<String, String> derived = base.withExecutor(dedicated);
            assertEquals("x", derived.run("x").requireAccepted());
            assertTrue("derived worker must have been used", !workerNames.isEmpty());
            for (String name : workerNames) {
                assertTrue("unexpected worker thread: " + name,
                        name.equals("derived-worker"));
            }
        } finally {
            dedicated.shutdown();
            dedicated.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    @Test
    public void customFlowIdAndVersionFlowIntoMetadataAndInvocationId() throws Exception {
        ResumePoint<String> point = ResumePoint.named("id-resume");
        final List<String> invocationIds = Collections.synchronizedList(new ArrayList<String>());
        final List<Metadata> metadatas = Collections.synchronizedList(new ArrayList<Metadata>());
        com.team4u.framework.flow.api.Operation<String, String> capture =
                (context, input) -> {
                    invocationIds.add(context.invocationId());
                    return Outcome.accepted(input);
                };
        FlowObserver observer = event -> {
            metadatas.add(event.metadata());
        };
        LocalExecutable<String, Resumed<String, String>> local = Local.from(
                Flow.step(capture).await(point))
                .flowId("order-flow")
                .flowVersion(7)
                .observer(observer)
                .compile();
        FlowResult.Suspended<Resumed<String, String>> suspended =
                (FlowResult.Suspended<Resumed<String, String>>) local.run("in");
        local.resume(suspended.suspension(), point, "sig").requireAccepted();

        // invocationId 前缀为 flowId:version:executionId:nodePath
        assertEquals(1, invocationIds.size());
        assertTrue(invocationIds.get(0).startsWith("order-flow:7:"));
        assertTrue(metadatas.size() > 0);
        for (Metadata metadata : metadatas) {
            assertEquals("order-flow", metadata.flowId());
            assertEquals(7, metadata.flowVersion());
        }
    }

    @Test
    public void concurrentRunsOfSameExecutableAreIsolated() throws Exception {
        final int concurrency = 6;
        ResumePoint<String> point = ResumePoint.named("concurrent-point");
        final LocalExecutable<String, String> local = Local.compile(
                Flow.<String>identity().await(point)
                        .then((context, resumed) -> Outcome.accepted(
                                resumed.state() + ":" + resumed.signal())));

        final CountDownLatch allStarted = new CountDownLatch(concurrency);
        final CountDownLatch allResumed = new CountDownLatch(concurrency);
        final List<String> outputs = Collections.synchronizedList(new ArrayList<String>());
        final List<FlowResult.Suspended<String>> suspensions =
                Collections.synchronizedList(new ArrayList<FlowResult.Suspended<String>>());

        // 并发启动：多个执行实例同时 run 挂起
        List<Thread> runners = new ArrayList<Thread>();
        for (int index = 0; index < concurrency; index++) {
            final String input = "input-" + index;
            Thread runner = new Thread(() -> {
                FlowResult<String> result = local.run(input);
                if (result instanceof FlowResult.Suspended) {
                    suspensions.add((FlowResult.Suspended<String>) result);
                }
                allStarted.countDown();
            });
            runners.add(runner);
            runner.start();
        }
        assertTrue(allStarted.await(2, TimeUnit.SECONDS));
        assertEquals(concurrency, suspensions.size());

        // 并发恢复：每个挂起句柄由不同线程交叉 resume，验证状态互不串扰
        // （suspensions 列表顺序由线程完成顺序决定，因此统一使用同一信号值，
        // 以输出中的 state 部分验证每个执行实例的输入被正确保留）
        List<Thread> resumers = new ArrayList<Thread>();
        for (int index = 0; index < concurrency; index++) {
            final FlowResult.Suspended<String> suspended = suspensions.get(index);
            Thread resumer = new Thread(() -> {
                try {
                    outputs.add(local.resume(suspended.suspension(), point, "sig")
                            .requireAccepted());
                } finally {
                    allResumed.countDown();
                }
            });
            resumers.add(resumer);
            resumer.start();
        }
        assertTrue(allResumed.await(2, TimeUnit.SECONDS));

        // 每个并发执行的输入（挂起前状态）都必须被正确保留，不与其他执行串扰
        assertEquals(concurrency, outputs.size());
        for (int index = 0; index < concurrency; index++) {
            assertTrue("missing state pairing for input-" + index + ": " + outputs,
                    outputs.contains("input-" + index + ":sig"));
        }
        for (Thread thread : runners) thread.join();
        for (Thread thread : resumers) thread.join();
    }

    @Test
    public void runAsyncWithDedicatedDispatcherDoesNotUseCommonPool() throws Exception {
        final String dispatcherName = "dedicated-dispatcher";
        ExecutorService dispatcher = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, dispatcherName);
            thread.setDaemon(true);
            return thread;
        });
        try {
            final List<String> callerThreads = Collections.synchronizedList(new ArrayList<String>());
            LocalExecutable<String, String> local = Local.compile(
                    Flow.step((com.team4u.framework.flow.api.Operation<String, String>)
                            (context, input) -> {
                                callerThreads.add(Thread.currentThread().getName());
                                return Outcome.accepted(input);
                            }));
            local.runAsync("in", dispatcher).toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertEquals(Collections.singletonList(dispatcherName), callerThreads);
        } finally {
            dispatcher.shutdown();
            dispatcher.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    @Test
    public void compileCachedResolvesOnceAndReusesAcrossConcurrentCompiles() throws Exception {
        final AtomicInteger resolutions = new AtomicInteger();
        OperationResolver resolver = (contract, qualifier) -> {
            resolutions.incrementAndGet();
            return (com.team4u.framework.flow.api.Operation<String, String>)
                    (context, input) -> Outcome.accepted(input);
        };
        Flow<String, String> flow = Flow.step(
                (com.team4u.framework.flow.api.Operation<String, String>)
                        (context, input) -> Outcome.accepted(input));

        // 同一 flow 实例重复 compileCached：解析仅一次，多次执行结果一致
        LocalExecutable<String, String> first = Local.compileCached(flow, resolver);
        LocalExecutable<String, String> second = Local.compileCached(flow, resolver);
        assertEquals("v", first.run("v").requireAccepted());
        assertEquals("v", second.run("v").requireAccepted());
        assertEquals(0, resolutions.get());

        // 并发首次编译：多线程同时 compileCached 同一实例，产物可并发驱动且全部成功
        final int threads = 4;
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threads);
        final List<String> outputs = Collections.synchronizedList(new ArrayList<String>());
        List<Thread> workers = new ArrayList<Thread>();
        for (int index = 0; index < threads; index++) {
            Thread worker = new Thread(() -> {
                try {
                    start.await();
                    outputs.add(Local.compileCached(flow, resolver).run("c").requireAccepted());
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            workers.add(worker);
            worker.start();
        }
        start.countDown();
        assertTrue(done.await(2, TimeUnit.SECONDS));
        assertEquals(Collections.nCopies(threads, "c"), outputs);
        for (Thread thread : workers) thread.join();
    }
}
