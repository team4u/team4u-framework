package com.team4u.framework.flow;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import com.team4u.framework.flow.compiler.Logical;
import com.team4u.framework.flow.compiler.LogicalLowerer;
import com.team4u.framework.flow.compiler.LogicalLowererRegistry;
import com.team4u.framework.flow.compiler.PlanNode;
import com.team4u.framework.flow.compiler.PlanNodeProjectorRegistry;
import com.team4u.framework.flow.desc.LogicalDescriberRegistry;
import com.team4u.framework.flow.engine.ControlKindRegistry;
import com.team4u.framework.flow.engine.FrameReducePolicyRegistry;
import com.team4u.framework.flow.engine.NodeExecutionHandlerRegistry;
import com.team4u.framework.flow.spi.NodeDescriptor;

/**
 * 注册表冻结契约验证：core 内核六大策略注册表在静态初始化注册完毕后冻结，
 * 冻结后任何写入操作必须抛出 UnsupportedOperationException，读取与正常扩展点不受影响。
 */
public class RegistryFreezeTest {

    @Test
    public void globalRegistriesAreFrozenAfterStaticRegistration() {
        assertTrue(NodeExecutionHandlerRegistry.global().isFrozen());
        assertTrue(FrameReducePolicyRegistry.global().isFrozen());
        assertTrue(ControlKindRegistry.global().isFrozen());
        assertTrue(LogicalLowererRegistry.global().isFrozen());
        assertTrue(PlanNodeProjectorRegistry.global().isFrozen());
        assertTrue(LogicalDescriberRegistry.global().isFrozen());
    }

    @Test
    public void frozenRegistriesRejectAllWrites() {
        assertFrozen(() -> NodeExecutionHandlerRegistry.global()
                .register(new FakeExecutionHandler()));
        assertFrozen(() -> FrameReducePolicyRegistry.global().unregisterAll());
        assertFrozen(() -> ControlKindRegistry.global().unregisterAll());
        assertFrozen(() -> LogicalLowererRegistry.global().unregisterAll());
        assertFrozen(() -> PlanNodeProjectorRegistry.global().unregisterAll());
        assertFrozen(() -> LogicalDescriberRegistry.global().unregisterAll());
    }

    @Test
    public void frozenRegistriesStillServeReadsAndFlowExecutes() {
        // 冻结后扩展点读取仍可用：编译与执行完整链路不回归
        assertEquals("value", Local.compile(Flow.<String>identity()).run("value")
                .requireAccepted());
        assertTrue(NodeExecutionHandlerRegistry.global()
                .get(PlanNode.Invoke.class).isPresent());
        assertTrue(ControlKindRegistry.global()
                .get(PlanNode.Control.Kind.POLICY).isPresent());
        assertFalse(LogicalLowererRegistry.global().getPolicies().isEmpty());
    }

    @Test
    public void customInstanceRemainsWritableUntilExplicitlyFrozen() {
        NodeExecutionHandlerRegistry custom = new NodeExecutionHandlerRegistry();
        assertFalse(custom.isFrozen());
        FakeExecutionHandler handler = new FakeExecutionHandler();
        custom.register(handler);
        assertSame(handler, custom.get(FakeNode.class).get());
        custom.unregister(handler);
        assertFalse(custom.get(FakeNode.class).isPresent());
        custom.freeze();
        assertFrozen(() -> custom.register(handler));
    }

    private static void assertFrozen(Runnable write) {
        try {
            write.run();
            fail("expected UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) {
            assertTrue(expected.getMessage().contains("frozen"));
        }
    }

    /** 测试专用 PlanNode 子类型（仅作注册键）。 */
    static final class FakeNode implements PlanNode {
        @Override
        public NodeDescriptor descriptor() {
            return NodeDescriptor.structural("$fake", "fake", NodeDescriptor.Kind.INVOKE);
        }
    }

    static final class FakeExecutionHandler
            implements com.team4u.framework.flow.engine.NodeExecutionHandler<FakeNode> {
        final AtomicInteger executions = new AtomicInteger();

        @Override
        public Class<? extends PlanNode> key() {
            return FakeNode.class;
        }

        @Override
        public com.team4u.framework.flow.engine.MachineResult execute(
                FakeNode node, com.team4u.framework.flow.engine.RuntimeFrame frame,
                com.team4u.framework.flow.engine.SerialMachine machine) {
            executions.incrementAndGet();
            return null;
        }
    }
}
