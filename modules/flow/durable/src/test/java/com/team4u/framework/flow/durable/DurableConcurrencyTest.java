package com.team4u.framework.flow.durable;

import com.team4u.framework.flow.Flow;
import com.team4u.framework.flow.api.Operation;
import com.team4u.framework.flow.api.OperationContext;
import com.team4u.framework.flow.api.PersistentPolicy;
import com.team4u.framework.flow.api.PolicyContext;
import com.team4u.framework.flow.api.ResumePoint;
import com.team4u.framework.flow.durable.snapshot.DurableSnapshot;
import com.team4u.framework.flow.durable.store.InMemoryDurableStore;
import com.team4u.framework.flow.model.Completion;
import com.team4u.framework.flow.model.Outcome;
import com.team4u.framework.flow.model.Resumed;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import com.team4u.framework.flow.durable.store.DurableStore;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 组14：并发命令矩阵 -- 真实双线程 + CountDownLatch 同时起跑编排，验证 CAS 乐观锁
 * 在并发 start/recover/resume/cancel 下的正确性：恰好一方成功、另一方明确错误码、
 * 存储 revision 单调且无中间态丢失。
 *
 * <p>遵守测试规范：线程会合使用 CountDownLatch 条件等待，无硬编码 sleep。</p>
 */
public class DurableConcurrencyTest {

    private ExecutorService pool;

    @Before
    public void setUp() {
        pool = Executors.newFixedThreadPool(2);
    }

    @After
    public void tearDown() {
        pool.shutdownNow();
    }

    private interface Command {
        void run() throws Exception;
    }

    /** 在双线程池上同时起跑两个命令，等待双方结束并返回双方结果（异常或 null）。 */
    private Outcome2 race(final Command first, final Command second) throws Exception {
        final CountDownLatch ready = new CountDownLatch(2);
        final CountDownLatch go = new CountDownLatch(1);
        final AtomicReference<Throwable> e1 = new AtomicReference<Throwable>();
        final AtomicReference<Throwable> e2 = new AtomicReference<Throwable>();
        Future<?> f1 = pool.submit(new Runnable() {
            @Override
            public void run() {
                ready.countDown();
                try {
                    go.await(5, TimeUnit.SECONDS);
                    first.run();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } catch (Throwable failure) {
                    e1.compareAndSet(null, failure);
                }
            }
        });
        Future<?> f2 = pool.submit(new Runnable() {
            @Override
            public void run() {
                ready.countDown();
                try {
                    go.await(5, TimeUnit.SECONDS);
                    second.run();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } catch (Throwable failure) {
                    e2.compareAndSet(null, failure);
                }
            }
        });
        // 双方就绪后同时放行，保证真实并发窗口
        assertTrue("双线程必须在 5 秒内就绪", ready.await(5, TimeUnit.SECONDS));
        go.countDown();
        f1.get(10, TimeUnit.SECONDS);
        f2.get(10, TimeUnit.SECONDS);
        return new Outcome2(e1.get(), e2.get());
    }

    /** 双方命令的执行结果：异常为 null 表示成功。 */
    private static final class Outcome2 {
        final Throwable first;
        final Throwable second;

        Outcome2(Throwable first, Throwable second) {
            this.first = first;
            this.second = second;
        }

        int successes() {
            return (first == null ? 1 : 0) + (second == null ? 1 : 0);
        }

        Throwable loser() {
            return first != null ? first : second;
        }

        Throwable winnerOrNone() {
            return first != null ? second : first;
        }
    }

    private static void assertDurableError(Throwable error, String context) {
        assertTrue(context + " 失败方必须是 DurableException，实际: " + error,
                error instanceof DurableException);
    }

    private static Operation<String, String> append(final String suffix) {
        return new Operation<String, String>() {
            @Override
            public Outcome<String> execute(OperationContext context, String input) {
                return Outcome.accepted(input + suffix);
            }
        };
    }

    // ------------------------------------------------------------------
    // 并发 start 同一 executionId
    // ------------------------------------------------------------------

    @Test
    public void concurrentStartAllowsExactlyOneWinner() throws Exception {
        final InMemoryDurableStore store = new InMemoryDurableStore();
        final DurableExecutable<String, String> executable =
                Durable.builder(store).build()
                        .compile(Flow.<String, String>step(append("-a")).then(append("-b")),
                                "conc", 1);

        Outcome2 raced = race(new Command() {
            @Override
            public void run() {
                executable.start("e-start", "in");
            }
        }, new Command() {
            @Override
            public void run() {
                executable.start("e-start", "in");
            }
        });

        assertEquals("start 竞争必须恰好一方成功", 1, raced.successes());
        Throwable loser = raced.loser();
        assertNotNull("必须有一个失败方", loser);
        assertDurableError(loser, "start");
        DurableException.Error code = ((DurableException) loser).error();
        assertTrue("失败码必须为 EXECUTION_EXISTS 或 REVISION_CONFLICT，实际: " + code,
                code == DurableException.Error.EXECUTION_EXISTS
                        || code == DurableException.Error.REVISION_CONFLICT);
        // 存储恰好一条记录、revision 单调（初始 0，之后两次推进至 2）
        DurableSnapshot snapshot = store.load("e-start").get();
        assertEquals(DurableLifecycle.COMPLETED, snapshot.lifecycle());
        assertEquals(2L, snapshot.revision());
    }

    // ------------------------------------------------------------------
    // 并发 recover 同一 ACTIVE 快照
    // ------------------------------------------------------------------

    /** 构造一个可推进的退避等待执行：首次失败后 RetryAt(afterMillis)，后续失败 RetryAt(60s)。 */
    private static DurableExecutable<String, String> retryBackoffExecutable(
            DurableStore store, final long firstBackoffMillis,
            final AtomicInteger bodyCalls) {
        Operation<String, String> flaky = new Operation<String, String>() {
            @Override
            public Outcome<String> execute(OperationContext context, String input) {
                bodyCalls.incrementAndGet();
                return Outcome.failed(com.team4u.framework.flow.model.Failure.of("X", "x"));
            }
        };
        Flow<String, String> flow = Flow.<String, String>step(flaky)
                .persistentPolicy(new PersistentPolicy<String, Integer>() {
                    @Override
                    public Integer initialState(String key) {
                        return 1;
                    }

                    @Override
                    public Before<Integer> before(PolicyContext ctx, String key,
                                                  Integer state) {
                        return PersistentPolicy.proceed(state);
                    }

                    @Override
                    public After<Integer> after(PolicyContext ctx, String key, Integer state,
                                                Completion completion) {
                        long backoff = state == 1 ? firstBackoffMillis : 60_000L;
                        return PersistentPolicy.retryAt(
                                java.time.Instant.now().plusMillis(backoff), state + 1);
                    }
                }, s -> s);
        return Durable.builder(store).build().compile(flow, "conc", 1);
    }

    @Test
    public void concurrentRecoverAllowsOnlyOneDriver() throws Exception {
        final InMemoryDurableStore realStore = new InMemoryDurableStore();
        final AtomicInteger bodyCalls = new AtomicInteger();
        final CountDownLatch bothLoaded = new CountDownLatch(2);
        final java.util.Set<Long> loadedThreadIds = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<Long, Boolean>());
        final AtomicLong expectedRaceRevision = new AtomicLong(-1);
        final DurableStore store = new DurableStore() {
            @Override
            public Optional<DurableSnapshot> load(String executionId) {
                Optional<DurableSnapshot> result = realStore.load(executionId);
                if ("e-recover".equals(executionId) && expectedRaceRevision.get() != -1) {
                    if (loadedThreadIds.add(Thread.currentThread().getId())) {
                        bothLoaded.countDown();
                    }
                }
                return result;
            }

            @Override
            public boolean compareAndSet(String executionId, long expectedRevision,
                                         DurableSnapshot update) {
                if ("e-recover".equals(executionId) && expectedRevision == expectedRaceRevision.get()) {
                    try {
                        bothLoaded.await(500, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return realStore.compareAndSet(executionId, expectedRevision, update);
            }
        };

        // 首次退避 30ms：等待其过去后 recover 会真实驱动并提交新检查点
        final DurableExecutable<String, String> executable =
                retryBackoffExecutable(store, 30L, bodyCalls);
        DurableResult<String> started = executable.start("e-recover", "in");
        assertTrue(started.getClass().getSimpleName(), started instanceof DurableResult.Active);
        final java.time.Instant firstWake =
                ((DurableResult.Active<String>) started).wakeAt().get();
        // 条件等待首退避过去（不硬编码 sleep）
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (java.time.Instant.now().isBefore(firstWake)
                && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertTrue("首退避必须在 5 秒内过去", !java.time.Instant.now().isBefore(firstWake));
        final long revisionBeforeRace = realStore.load("e-recover").get().revision();
        expectedRaceRevision.set(revisionBeforeRace);

        Outcome2 raced = race(new Command() {
            @Override
            public void run() {
                executable.recover("e-recover");
            }
        }, new Command() {
            @Override
            public void run() {
                executable.recover("e-recover");
            }
        });

        assertEquals("recover 竞争必须恰好一方成功", 1, raced.successes());
        Throwable loser = raced.loser();
        assertNotNull("必须有一个失败方", loser);
        assertDurableError(loser, "recover");
        assertEquals("recover 失败码必须为 REVISION_CONFLICT",
                DurableException.Error.REVISION_CONFLICT, ((DurableException) loser).error());
        // 胜者恰好重放一次 body（失败者首次提交即被拒，不重放）
        assertEquals("body 重放次数必须为 1（仅胜者）", 2, bodyCalls.get());
        // revision 单调推进且无跳变：胜者单个 recover 经过三个检查点边界
        // （Proceed 进入 + finish 交接 + RetryAt 退避），每次恰好 +1，无中间态丢失
        long revisionAfter = realStore.load("e-recover").get().revision();
        assertEquals("revision 必须恰好推进三版（单个 recover 的三个检查点）",
                revisionBeforeRace + 3, revisionAfter);
    }

    // ------------------------------------------------------------------
    // resume 与 cancel 交错
    // ------------------------------------------------------------------

    private static Flow<String, String> awaitThenEchoFlow(ResumePoint<String> gate) {
        return Flow.<String, String>step(append("-pre"))
                .<String>await(gate)
                .then(new Operation<Resumed<String, String>, String>() {
                    @Override
                    public Outcome<String> execute(OperationContext context,
                                                   Resumed<String, String> input) {
                        return Outcome.accepted(input.state() + "#" + input.signal());
                    }
                });
    }

    @Test
    public void resumeInterleavedWithCancelKeepsConsistentState() throws Exception {
        final InMemoryDurableStore store = new InMemoryDurableStore();
        final ResumePoint<String> gate = ResumePoint.named("gate");
        final DurableExecutable<String, String> executable =
                Durable.builder(store).build()
                        .compile(awaitThenEchoFlow(gate), "conc", 1);
        DurableResult<String> suspended = executable.start("e-mix", "in");
        assertTrue(suspended.getClass().getSimpleName(),
                suspended instanceof DurableResult.Suspended);

        Outcome2 raced = race(new Command() {
            @Override
            public void run() {
                executable.resume("e-mix", "gate", "GO");
            }
        }, new Command() {
            @Override
            public void run() {
                executable.cancel("e-mix");
            }
        });

        // 终态一致性：要么 COMPLETED（resume 胜）要么 CANCELLED（cancel 胜），
        // 不允许残留 SUSPENDED/ACTIVE 中间态
        DurableSnapshot snapshot = store.load("e-mix").get();
        assertTrue("交错后必须落定终态，实际: " + snapshot.lifecycle(),
                snapshot.lifecycle() == DurableLifecycle.COMPLETED
                        || snapshot.lifecycle() == DurableLifecycle.CANCELLED);
        // 至少一方失败或 cancel 恰在 resume 完成前落败：双成功不可能（cancel 只能作用于非终态）
        assertTrue("resume/cancel 交错必须至少一方失败",
                raced.successes() <= 1);
        // 失败方错误码明确
        Throwable loser = raced.loser();
        if (loser != null) {
            assertDurableError(loser, "resume-cancel 交错");
        }
        // revision 单调
        assertTrue(snapshot.revision() >= suspended.snapshot().revision());
    }

    // ------------------------------------------------------------------
    // 双 resume 并发同值信号
    // ------------------------------------------------------------------

    @Test
    public void concurrentDuplicateResumeWithSameSignalEndsConsistently() throws Exception {
        final InMemoryDurableStore store = new InMemoryDurableStore();
        final ResumePoint<String> gate = ResumePoint.named("gate");
        final DurableExecutable<String, String> executable =
                Durable.builder(store).build()
                        .compile(awaitThenEchoFlow(gate), "conc", 1);
        DurableResult<String> suspended = executable.start("e-dup", "in");
        assertEquals(DurableLifecycle.SUSPENDED,
                store.load("e-dup").get().lifecycle());

        final AtomicInteger completedEchoes = new AtomicInteger();
        Outcome2 raced = race(new Command() {
            @Override
            public void run() {
                DurableResult<String> result = executable.resume("e-dup", "gate", "GO");
                if (result instanceof DurableResult.Completed) {
                    DurableResult.Completed<String> done = (DurableResult.Completed<String>) result;
                    if ("in-pre#GO".equals(((Outcome.Accepted<String>) done.outcome()).value())) {
                        completedEchoes.incrementAndGet();
                    }
                }
            }
        }, new Command() {
            @Override
            public void run() {
                DurableResult<String> result = executable.resume("e-dup", "gate", "GO");
                if (result instanceof DurableResult.Completed) {
                    DurableResult.Completed<String> done = (DurableResult.Completed<String>) result;
                    if ("in-pre#GO".equals(((Outcome.Accepted<String>) done.outcome()).value())) {
                        completedEchoes.incrementAndGet();
                    }
                }
            }
        });

        // 同值信号并发 resume：一成功一幂等（可能双方均到达 Completed，或一方冲突失败）
        assertTrue("必须至少一方成功", raced.successes() >= 1);
        // 失败方错误码必须明确（不允许裸异常）
        Throwable loser = raced.loser();
        if (loser != null) {
            assertDurableError(loser, "并发同值 resume");
        }
        // 终态一致：至少一方驱动完成则 COMPLETED，且业务值正确
        DurableSnapshot snapshot = store.load("e-dup").get();
        assertTrue("并发同值 resume 后必须落定 COMPLETED，实际: " + snapshot.lifecycle(),
                snapshot.lifecycle() == DurableLifecycle.COMPLETED);
        assertEquals("完成值必须恰好消费一次信号", 1, completedEchoes.get());
        assertTrue("revision 必须单调",
                snapshot.revision() >= suspended.snapshot().revision());
    }
}
