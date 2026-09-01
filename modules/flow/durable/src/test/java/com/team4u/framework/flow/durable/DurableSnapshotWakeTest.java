package com.team4u.framework.flow.durable;

import com.team4u.framework.flow.Flow;
import org.junit.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static com.team4u.framework.flow.durable.DurableTestOps.FailingMapper;
import static com.team4u.framework.flow.durable.DurableTestOps.RecordingOp;
import static com.team4u.framework.flow.durable.DurableTestOps.SimulatedCrash;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import com.team4u.framework.flow.api.Operation;
import com.team4u.framework.flow.api.OperationContext;
import com.team4u.framework.flow.api.PersistentPolicy;
import com.team4u.framework.flow.api.PolicyContext;
import com.team4u.framework.flow.api.ResumePoint;
import com.team4u.framework.flow.durable.snapshot.DurableSnapshot;
import com.team4u.framework.flow.durable.store.InMemoryDurableStore;
import com.team4u.framework.flow.model.Completion;
import com.team4u.framework.flow.model.Failure;
import com.team4u.framework.flow.model.Outcome;

/**
 * 组15：信封 wake 冗余与编解码失败路径 -- firstWakeAt 信封字段在 RetryAt 退避下
 * 正确填充、恢复推进后清空/更新；FailingMapper 触发 CODEC_FAILURE（commit 编码失败
 * 与 recover 解码失败）。
 */
public class DurableSnapshotWakeTest {

    private static final ResumePoint<String> GATE = ResumePoint.named("gate");

    /** 恒定失败并按 RetryAt 退避的策略。 */
    private static PersistentPolicy<String, Integer> retryPolicy(final long backoffMillis) {
        return new PersistentPolicy<String, Integer>() {
            @Override
            public Integer initialState(String key) {
                return 1;
            }

            @Override
            public Before<Integer> before(PolicyContext ctx, String key, Integer state) {
                return PersistentPolicy.proceed(state);
            }

            @Override
            public After<Integer> after(PolicyContext ctx, String key, Integer state,
                                        Completion completion) {
                return PersistentPolicy.retryAt(
                        Instant.now().plusMillis(backoffMillis), state + 1);
            }
        };
    }

    @Test
    public void firstWakeAtIsFilledOnRetryAtParkAndClearedAfterProgress() {
        InMemoryDurableStore store = new InMemoryDurableStore();
        final AtomicInteger calls = new AtomicInteger();
        Operation<String, String> flaky = new Operation<String, String>() {
            @Override
            public Outcome<String> execute(OperationContext context, String input) {
                if (calls.incrementAndGet() == 1) {
                    return Outcome.failed(Failure.of("SOFT", "retry me"));
                }
                return Outcome.accepted(input + ">ok");
            }
        };
        // 第一次失败后 RetryAt 退避 40ms，第二次成功完成
        PersistentPolicy<String, Integer> policy = new PersistentPolicy<String, Integer>() {
            @Override
            public Integer initialState(String key) {
                return 1;
            }

            @Override
            public Before<Integer> before(PolicyContext ctx, String key, Integer state) {
                return PersistentPolicy.proceed(state);
            }

            @Override
            public After<Integer> after(PolicyContext ctx, String key, Integer state,
                                        Completion completion) {
                if (completion != null && completion.kind() == Outcome.Kind.FAILED) {
                    return PersistentPolicy.retryAt(Instant.now().plusMillis(40), state + 1);
                }
                return PersistentPolicy.returning(state);
            }
        };
        Flow<String, String> flow = Flow.<String, String>step(flaky)
                .persistentPolicy(policy, s -> s);
        DurableExecutable<String, String> executable =
                Durable.builder(store).build().compile(flow, "wake", 1);

        // 首驱动：失败 → RetryAt 退避挂起（ACTIVE + wake）
        DurableResult<String> first = executable.start("e", "in");
        assertTrue(first.getClass().getSimpleName(), first instanceof DurableResult.Active);
        Instant driveWakeAt = ((DurableResult.Active<String>) first).wakeAt().get();
        // 信封 firstWakeAt 必须与驱动结果 wakeAt 一致（冗余字段从帧栈计算）
        DurableSnapshot parked = store.load("e").get();
        assertEquals(DurableLifecycle.ACTIVE, parked.lifecycle());
        assertNotNull("退避挂起快照必须携带 firstWakeAt", parked.firstWakeAt());
        assertEquals(driveWakeAt, parked.firstWakeAt());

        // 条件等待退避过去（不硬编码 sleep）
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (Instant.now().isBefore(driveWakeAt) && System.nanoTime() < deadline) {
            try {
                Thread.sleep(5);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }

        // 恢复推进：重试成功 → COMPLETED，终态快照必须清空 firstWakeAt
        DurableResult<String> done = executable.recover("e");
        assertTrue(done.getClass().getSimpleName(), done instanceof DurableResult.Completed);
        DurableSnapshot completed = store.load("e").get();
        assertEquals(DurableLifecycle.COMPLETED, completed.lifecycle());
        assertNull("终态快照必须清空 firstWakeAt", completed.firstWakeAt());
        assertEquals("in>ok", ((Outcome.Accepted<String>)
                ((DurableResult.Completed<String>) done).outcome()).value());
    }

    @Test
    public void firstWakeAtUpdatesOnSubsequentRetryBackoff() {
        InMemoryDurableStore store = new InMemoryDurableStore();
        Operation<String, String> alwaysFails = new Operation<String, String>() {
            @Override
            public Outcome<String> execute(OperationContext context, String input) {
                return Outcome.failed(Failure.of("X", "x"));
            }
        };
        // 首次退避 40ms，后续退避 60s：新 firstWakeAt 必须晚于旧值
        PersistentPolicy<String, Integer> policy = new PersistentPolicy<String, Integer>() {
            @Override
            public Integer initialState(String key) {
                return 1;
            }

            @Override
            public Before<Integer> before(PolicyContext ctx, String key, Integer state) {
                return PersistentPolicy.proceed(state);
            }

            @Override
            public After<Integer> after(PolicyContext ctx, String key, Integer state,
                                        Completion completion) {
                long backoff = state == 1 ? 40L : 60_000L;
                return PersistentPolicy.retryAt(
                        Instant.now().plusMillis(backoff), state + 1);
            }
        };
        Flow<String, String> flow = Flow.<String, String>step(alwaysFails)
                .persistentPolicy(policy, s -> s);
        DurableExecutable<String, String> executable =
                Durable.builder(store).build().compile(flow, "wake2", 1);
        DurableResult<String> first = executable.start("e", "in");
        Instant firstWake = ((DurableResult.Active<String>) first).wakeAt().get();
        assertEquals(firstWake, store.load("e").get().firstWakeAt());
        // 条件等待首次退避过去（不硬编码 sleep）
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (Instant.now().isBefore(firstWake) && System.nanoTime() < deadline) {
            try {
                Thread.sleep(5);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        DurableResult<String> second = executable.recover("e");
        assertTrue(second.getClass().getSimpleName(), second instanceof DurableResult.Active);
        Instant secondWake = ((DurableResult.Active<String>) second).wakeAt().get();
        DurableSnapshot updated = store.load("e").get();
        assertEquals("恢复后快照 firstWakeAt 必须更新为新退避时刻",
                secondWake, updated.firstWakeAt());
        assertTrue("新退避时刻必须晚于旧时刻",
                updated.firstWakeAt().isAfter(firstWake));
    }

    // ------------------------------------------------------------------
    // CODEC_FAILURE 路径（FailingMapper）
    // ------------------------------------------------------------------

    @Test
    public void encodeFailureAtCommitFailsWithCodecFailure() {
        InMemoryDurableStore store = new InMemoryDurableStore();
        // 业务输入为不支持编码的对象类型：初始检查点编码即失败
        Flow<Object, String> flow = Flow.<Object, String>step(
                new com.team4u.framework.flow.api.Operation<Object, String>() {
                    @Override
                    public Outcome<String> execute(OperationContext ctx, Object in) {
                        return Outcome.accepted(String.valueOf(in));
                    }
                });
        DurableExecutable<Object, String> executable = Durable.builder(store)
                .stateMapper(new FailingMapper())
                .build()
                .compile(flow, "codec", 1);
        try {
            executable.start("e", new Object());
            fail("编码失败必须 CODEC_FAILURE");
        } catch (DurableException error) {
            assertEquals(DurableException.Error.CODEC_FAILURE, error.error());
        }
        // 未落任何快照
        assertFalse(store.load("e").isPresent());
    }

    @Test
    public void decodeFailureAtRecoverFailsWithCodecFailure() {
        InMemoryDurableStore store = new InMemoryDurableStore();
        // 正常 mapper 先落一个退避挂起（ACTIVE+wake）快照，再以 FailingMapper 恢复（解码失败）
        Operation<String, String> alwaysFails = new Operation<String, String>() {
            @Override
            public Outcome<String> execute(OperationContext context, String input) {
                return Outcome.failed(Failure.of("X", "x"));
            }
        };
        Flow<String, String> flow = Flow.<String, String>step(alwaysFails)
                .persistentPolicy(retryPolicy(60_000), s -> s);
        Durable runtime = Durable.builder(store).build();
        DurableExecutable<String, String> normal = runtime.compile(flow, "codec", 1);
        normal.start("e", "in");
        assertEquals(DurableLifecycle.ACTIVE, store.load("e").get().lifecycle());

        DurableExecutable<String, String> broken = Durable.builder(store)
                .stateMapper(new FailingMapper())
                .build()
                .compile(flow, "codec", 1);
        try {
            broken.recover("e");
            fail("解码失败必须 CODEC_FAILURE");
        } catch (DurableException error) {
            assertEquals(DurableException.Error.CODEC_FAILURE, error.error());
        }
        // 快照未被破坏：正常 mapper 仍可推进
        DurableResult<String> resumed = normal.recover("e");
        assertTrue(resumed.getClass().getSimpleName(), resumed instanceof DurableResult.Active);
    }
}
