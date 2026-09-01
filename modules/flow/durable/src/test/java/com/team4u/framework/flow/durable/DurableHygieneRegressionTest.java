package com.team4u.framework.flow.durable;

import com.team4u.framework.flow.Flow;
import org.junit.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import com.team4u.framework.flow.api.PersistentPolicy;
import com.team4u.framework.flow.durable.engine.DurablePlanCompiler;
import com.team4u.framework.flow.durable.engine.DurableState;
import com.team4u.framework.flow.durable.snapshot.DefaultStateMapper;
import com.team4u.framework.flow.durable.snapshot.DurableSnapshot;
import com.team4u.framework.flow.durable.snapshot.SnapshotCodec;
import com.team4u.framework.flow.durable.store.InMemoryDurableStore;
import com.team4u.framework.flow.spi.OperationResolver;
import com.team4u.framework.flow.api.Operation;
import com.team4u.framework.flow.api.OperationContext;
import com.team4u.framework.flow.model.Failure;
import com.team4u.framework.flow.model.Outcome;

/**
 * 组13：卫生修复回归 — result().wakeAt 含 TIMEOUT deadline（与 Core 对齐）、
 * Route phase=2 游标显式区间校验（-1 <= index < cases）边界。
 */
public class DurableHygieneRegressionTest {

    private static Operation<String, String> fixedOp(final String tag) {
        return new Operation<String, String>() {
            @Override
            public Outcome<String> execute(OperationContext context, String input) {
                return Outcome.accepted(input + ">" + tag);
            }
        };
    }

    // ------------------------------------------------------------------
    // wakeAt 与 TIMEOUT deadline 对齐
    // ------------------------------------------------------------------

    @Test
    public void activeResultWakeAtIncludesTimeoutDeadline() {
        // 阻塞在 RETRY 退避（wake 很远）且被 TIMEOUT 包裹（deadline 更近）：
        // Active 结果的 wakeAt 必须等于较早的 deadline，而非远期 wake。
        final AtomicInteger calls = new AtomicInteger();
        Operation<String, String> flaky = new Operation<String, String>() {
            @Override
            public Outcome<String> execute(OperationContext context, String input) {
                calls.incrementAndGet();
                return Outcome.failed(
                        com.team4u.framework.flow.model.Failure.of("BAD", "always"));
            }
        };
        Flow<String, String> flow = Flow.<String, String>step(flaky)
                .persistentPolicy(new com.team4u.framework.flow.api.PersistentPolicy<String, Integer>() {
                    @Override public Integer initialState(String key) { return 1; }
                    @Override public Before<Integer> before(com.team4u.framework.flow.api.PolicyContext ctx, String key, Integer state) {
                        return PersistentPolicy.proceed(state);
                    }
                    @Override public After<Integer> after(com.team4u.framework.flow.api.PolicyContext ctx, String key, Integer state, com.team4u.framework.flow.model.Completion completion) {
                        if (completion != null && completion.kind() == com.team4u.framework.flow.model.Outcome.Kind.FAILED && state < 3) {
                            return PersistentPolicy.retryAt(java.time.Instant.now().plus(Duration.ofSeconds(30)), state + 1);
                        }
                        return PersistentPolicy.returning(state);
                    }
                }, s -> s)
                .timeout(Duration.ofSeconds(2));
        // 行为变更：含 TIMEOUT 的定义在无 executor 时编译期 fail-fast
        try {
            Durable.builder(new InMemoryDurableStore()).build().compile(flow, "wake", 1);
            fail("含 TIMEOUT 而无 executor 必须 INVALID_CONFIGURATION");
        } catch (DurableException error) {
            assertEquals(DurableException.Error.INVALID_CONFIGURATION, error.error());
        }
        java.util.concurrent.ExecutorService executor =
                java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            InMemoryDurableStore store = new InMemoryDurableStore();
            DurableExecutable<String, String> executable =
                    Durable.builder(store).executor(executor).build()
                            .compile(flow, "wake", 1);
            DurableResult<String> result = executable.start("e", "in");
            assertTrue(result.getClass().getSimpleName(), result instanceof DurableResult.Active);
            DurableResult.Active<String> active = (DurableResult.Active<String>) result;
            assertTrue("wakeAt 必须存在", active.wakeAt().isPresent());
            Instant wakeAt = active.wakeAt().get();
            Instant now = Instant.now();
            // deadline=now+2s：wakeAt 应接近 deadline（远小于 30s 的 retry backoff）
            assertTrue("wakeAt 必须反映 TIMEOUT deadline 而非远期 backoff wake: " + wakeAt,
                    Duration.between(now, wakeAt).compareTo(Duration.ofSeconds(5)) < 0);
            assertTrue("wakeAt 不得早于当前时间", !wakeAt.isBefore(now.minusSeconds(1)));
            // 快照信封的 firstWakeAt 冗余字段与驱动结果一致
            DurableSnapshot snapshot = store.load("e").get();
            assertEquals(wakeAt, snapshot.firstWakeAt());
        } finally {
            executor.shutdownNow();
        }
    }

    // ------------------------------------------------------------------
    // Route phase=2 游标区间边界
    // ------------------------------------------------------------------

    /** 用指定 phase/index 的 Route 根帧构造快照并尝试恢复，返回解码异常（或 null 表示放行）。 */
    private DurableException restoreRouteFrameWith(int phase, int index, String selected) {
        Operation<String, String> selector = fixedOp("selector");
        Flow<String, String> flow = Flow.<String, String>route(selector)
                .caseOf("go", Flow.<String>identity())
                .withoutOtherwise();
        InMemoryDurableStore store = new InMemoryDurableStore();
        Durable runtime = Durable.builder(store).build();
        DurableExecutable<String, String> executable = runtime.compile(flow, "edge", 1);
        DurablePlanCompiler.Definition definition =
                DurablePlanCompiler.compile(flow, com.team4u.framework.flow.spi.OperationResolver.rejecting());
        // 手工构造帧栈：单帧 Route(phase, index)
        DurableState.RuntimeFrame frame = new DurableState.RuntimeFrame(
                definition.root(), "in", DurableState.SlotRole.user("input"));
        frame.phase = phase;
        frame.index = index;
        frame.selected = selected;
        ArrayList<DurableState.RuntimeFrame> frames = new ArrayList<DurableState.RuntimeFrame>();
        frames.add(frame);
        DurableState.MachineState state = new DurableState.MachineState("e", frames);
        SnapshotCodec.Payload payload = SnapshotCodec.encode(
                state, DefaultStateMapper.INSTANCE, definition.slotRoles());
        DurableSnapshot snapshot = new DurableSnapshot("e", "edge", 1,
                DurableSnapshot.CURRENT_FORMAT_ID, DurableSnapshot.CURRENT_FORMAT_VERSION,
                0L, DurableLifecycle.ACTIVE, payload.metadata(), payload.slots(),
                null, false);
        assertTrue("手工构造快照以 create 模式落库",
                store.compareAndSet("e", -1, snapshot));
        try {
            executable.recover("e");
            return null;
        } catch (DurableException error) {
            return error;
        } catch (IllegalStateException tolerated) {
            // 解码游标校验已放行；phase=2 无子帧属异常形态，由机器防御终止。
            return null;
        }
    }

    @Test
    public void routePhaseTwoIndexBelowMinusOneIsRejected() {
        DurableException error = restoreRouteFrameWith(2, -2, null);
        assertNotNull("index < -1 必须被拒绝", error);
        assertEquals(DurableException.Error.FRAME_MISMATCH, error.error());
    }

    @Test
    public void routePhaseTwoIndexAtCasesIsRejected() {
        // 单 case：index==cases 越界
        DurableException error = restoreRouteFrameWith(2, 1, "case:1");
        assertNotNull("index >= cases 必须被拒绝", error);
        assertEquals(DurableException.Error.FRAME_MISMATCH, error.error());
    }

    @Test
    public void routePhaseTwoIndexFarOutOfRangeIsRejected() {
        DurableException error = restoreRouteFrameWith(2, 99, "case:99");
        assertNotNull("远越界 index 必须被拒绝", error);
        assertEquals(DurableException.Error.FRAME_MISMATCH, error.error());
    }

    @Test
    public void routePhaseTwoMinusOneNoOtherwiseIsToleratedDefensively() {
        // index=-1 且 otherwise==null：仅存在于 no-match 完成瞬间（不会落库）。
        // 栈顶 phase=2 无子帧本身不会自然出现；游标数值自洽时解码校验必须放行，
        // 后续机器驱动行为不在本用例范围（否则会因 phase!=0 且无子帧而终止）。
        DurableException error = restoreRouteFrameWith(2, -1, null);
        assertEquals("no-match 完成瞬间的 -1 游标应防御性放行", null, error);
    }

    @Test
    public void routePhaseTwoValidCaseIndexPassesCursorCheck() {
        // index=0（首个 case）：游标合法。解码游标校验应放行；
        // 驱动行为不在本用例范围（phase=2 无子帧属异常形态，由机器防御终止）。
        DurableException error = restoreRouteFrameWith(2, 0, "case:0");
        assertEquals("合法 case 游标应放行", null, error);
    }

    // ------------------------------------------------------------------
    // 注册表冻结防护
    // ------------------------------------------------------------------

    @Test
    public void globalRegistriesAreFrozenAndRejectWrites() {
        // 三个全局注册表在静态初始化后已冻结：写入操作必须被拒绝，读取不受影响
        com.team4u.framework.flow.durable.engine.DurableControlKindRegistry control =
                com.team4u.framework.flow.durable.engine.DurableControlKindRegistry.global();
        com.team4u.framework.flow.durable.engine.DurableNodeExecutionHandlerRegistry nodes =
                com.team4u.framework.flow.durable.engine.DurableNodeExecutionHandlerRegistry.global();
        com.team4u.framework.flow.durable.engine.DurableFrameReducePolicyRegistry reducers =
                com.team4u.framework.flow.durable.engine.DurableFrameReducePolicyRegistry.global();

        assertTrue(control.isFrozen());
        assertTrue(nodes.isFrozen());
        assertTrue(reducers.isFrozen());

        try {
            control.unregisterAll();
            fail("冻结注册表写入必须被拒绝");
        } catch (UnsupportedOperationException expected) {
            // 冻结防护
        }
        try {
            nodes.unregisterAll();
            fail("冻结注册表写入必须被拒绝");
        } catch (UnsupportedOperationException expected) {
            // 冻结防护
        }
        try {
            reducers.unregisterAll();
            fail("冻结注册表写入必须被拒绝");
        } catch (UnsupportedOperationException expected) {
            // 冻结防护
        }

        // 读取不受影响：内置策略仍可路由
        assertTrue(control.get(com.team4u.framework.flow.spi.ControlKind.PERSISTENT_POLICY)
                .isPresent());
        assertTrue(nodes.get(com.team4u.framework.flow.durable.engine.DurablePlanNode.Invoke.class)
                .isPresent());
        assertTrue(reducers.get(
                        com.team4u.framework.flow.durable.engine.DurablePlanNode.Sequence.class)
                .isPresent());

        // 本地（非 global）实例不受冻结限制：可正常注册
        com.team4u.framework.flow.durable.engine.DurableControlKindRegistry local =
                new com.team4u.framework.flow.durable.engine.DurableControlKindRegistry();
        assertFalse(local.isFrozen());
        local.register(new com.team4u.framework.flow.durable.engine.DurableControlKindHandler() {
            @Override
            public com.team4u.framework.flow.spi.ControlKind key() {
                return com.team4u.framework.flow.spi.ControlKind.TIMEOUT;
            }

            @Override
            public void enter(com.team4u.framework.flow.durable.engine.DurablePlanNode.Control control,
                              DurableState.RuntimeFrame frame,
                              DurableMachine machine) {
                // 测试替身：不推进
            }

            @Override
            public DurableState.MachineOutcome reduce(
                    com.team4u.framework.flow.durable.engine.DurablePlanNode.Control control,
                    DurableState.RuntimeFrame frame,
                    DurableState.MachineOutcome child, DurableMachine machine) {
                return child;
            }
        });
        assertTrue(local.get(com.team4u.framework.flow.spi.ControlKind.TIMEOUT).isPresent());
    }
}
