package com.team4u.framework.flow.durable;

import com.team4u.framework.flow.Flow;
import org.junit.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import com.team4u.framework.flow.api.Retry;
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
                .retry(new com.team4u.framework.flow.api.Retry(3, Duration.ofSeconds(30)))
                .timeout(Duration.ofSeconds(2));
        InMemoryDurableStore store = new InMemoryDurableStore();
        DurableExecutable<String, String> executable =
                DurableRuntime.builder(store).build().compile(flow, "wake", 1);
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
        DurableRuntime runtime = DurableRuntime.builder(store).build();
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
                1L, DurableLifecycle.ACTIVE, payload.metadata(), payload.slots(),
                null, false);
        store.insertForTest("e", snapshot);  // 手工构造快照直接落库
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
}
