package com.team4u.framework.flow.durable;

import java.time.Duration;
import org.junit.Test;
import com.team4u.framework.flow.Flow;
import com.team4u.framework.flow.api.Operation;
import com.team4u.framework.flow.api.OperationContext;
import com.team4u.framework.flow.api.ResumePoint;
import com.team4u.framework.flow.durable.store.InMemoryDurableStore;
import com.team4u.framework.flow.model.Outcome;
import com.team4u.framework.flow.model.Resumed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Durable 模式下 Adapter 与 parallelFill 执行及断点续传测试。
 */
public class DurableAdapterTest {

    @Test
    public void durableAdapterExecution() {
        Flow<Integer, Integer> subflow = Flow.step((ctx, i) -> Outcome.accepted(i * 10));

        Flow<String, String> flow = Flow.<String>identity()
                .thenAdapt(subflow, Integer::parseInt, (s, r) -> s + "=" + r);

        InMemoryDurableStore store = new InMemoryDurableStore();
        DurableExecutable<String, String> executable = Durable.builder(store)
                .build()
                .compile(flow, "adapter-flow", 1);

        DurableResult<String> result = executable.start("e1", "7");
        assertTrue(result instanceof DurableResult.Completed);
        assertEquals("7=70", result.requireAccepted());
        assertEquals(DurableLifecycle.COMPLETED, store.load("e1").get().lifecycle());
    }

    @Test
    public void durableAdapterWithAwaitAndResume() {
        ResumePoint<String> point = ResumePoint.named("approval");

        Flow<Integer, String> subflow = Flow.<Integer>identity()
                .await(point)
                .then((ctx, resumed) -> Outcome.accepted("approved:" + resumed.signal()));

        Flow<String, String> flow = Flow.<String>identity()
                .thenAdapt(subflow, Integer::parseInt, (orig, approved) -> orig + "->" + approved);

        InMemoryDurableStore store = new InMemoryDurableStore();
        DurableExecutable<String, String> executable = Durable.builder(store)
                .build()
                .compile(flow, "adapter-await-flow", 1);

        DurableResult<String> started = executable.start("e2", "42");
        assertTrue(started instanceof DurableResult.Suspended);
        assertEquals(DurableLifecycle.SUSPENDED, store.load("e2").get().lifecycle());

        DurableResult<String> resumed = executable.resume("e2", point.name(), "BOSS_OK");
        assertTrue(resumed instanceof DurableResult.Completed);
        assertEquals("42->approved:BOSS_OK", resumed.requireAccepted());
        assertEquals(DurableLifecycle.COMPLETED, store.load("e2").get().lifecycle());
    }

    @Test
    public void durableParallelFillExecution() {
        Operation<String, String> fetchVip = (ctx, id) -> Outcome.accepted("VIP");
        Operation<String, Integer> fetchStock = (ctx, id) -> Outcome.accepted(99);

        Flow<String, String> flow = Flow.<String>identity()
                .parallelFill()
                .fork(s -> s, fetchVip, (s, vip) -> s + ":" + vip)
                .fork(s -> s, fetchStock, (s, stock) -> s + ":stock=" + stock)
                .end();

        InMemoryDurableStore store = new InMemoryDurableStore();
        DurableExecutable<String, String> executable = Durable.builder(store)
                .build()
                .compile(flow, "parallel-fill-flow", 1);

        DurableResult<String> result = executable.start("e3", "ORD-999");

        assertTrue(result instanceof DurableResult.Completed);
        String finished = result.requireAccepted();
        assertEquals("ORD-999:VIP:stock=99", finished);
        assertEquals(DurableLifecycle.COMPLETED, store.load("e3").get().lifecycle());
    }
}
