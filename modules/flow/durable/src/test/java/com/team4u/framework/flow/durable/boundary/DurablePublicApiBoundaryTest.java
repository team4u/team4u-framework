package com.team4u.framework.flow.durable.boundary;

import com.team4u.framework.flow.Flow;
import com.team4u.framework.flow.durable.DurableExecutable;
import com.team4u.framework.flow.durable.DurableLifecycle;
import com.team4u.framework.flow.durable.DurableResult;
import com.team4u.framework.flow.durable.Durable;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import com.team4u.framework.flow.compiler.Logical;
import com.team4u.framework.flow.compiler.PlanNode;
import com.team4u.framework.flow.api.Operation;
import com.team4u.framework.flow.api.OperationContext;
import com.team4u.framework.flow.durable.store.InMemoryDurableStore;
import com.team4u.framework.flow.model.Outcome;

/**
 * 组9（编译边界）：本测试位于独立子包，只 import Core 公开 API 与 Durable 公开 API。
 * 能够编译通过本身即证明：Durable 生产代码对外只暴露可用的公开类型，
 * 无需任何 Core 内部类型（CoreDurableBridge/PlanNode/Logical 等）即可完整驱动执行。
 */
public class DurablePublicApiBoundaryTest {

    private static Operation<String, String> append(final String tag) {
        return new Operation<String, String>() {
            @Override
            public Outcome<String> execute(OperationContext context, String input) {
                return Outcome.accepted(input + ">" + tag);
            }
        };
    }

    @Test
    public void externalPackageDrivesFullLifecycleViaPublicApiOnly() {
        Flow<String, String> flow = Flow.<String, String>step(append("a")).then(append("b"));
        Durable runtime = Durable.builder(new InMemoryDurableStore()).build();
        DurableExecutable<String, String> executable = runtime.compile(flow, "boundary", 1);
        DurableResult<String> result = executable.start("e", "in");
        assertTrue(result instanceof DurableResult.Completed);
        assertEquals("in>a>b", ((Outcome.Accepted<String>)
                ((DurableResult.Completed<String>) result).outcome()).value());
        assertEquals(DurableLifecycle.COMPLETED,
                executable.snapshot("e").get().lifecycle());
    }
}
