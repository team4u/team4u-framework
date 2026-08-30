package com.team4u.framework.policy.engine;

import com.team4u.framework.policy.api.ContextPolicy;
import com.team4u.framework.policy.core.OrderedPolicyChain;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * 流水线组件的单元测试验证
 */
public class PolicyPipelineTest {

    @Test
    @SuppressWarnings("unchecked")
    public void testExecuteChainGoThrough() {
        OrderedPolicyChain<String, ContextPolicy<String>> engine = new OrderedPolicyChain<>(
                (Class) ContextPolicy.class);
        // 新增两组普通策略对象，使用不同的子类避免被当成同类覆盖
        engine.register(new DummyPolicyA("A"));
        engine.register(new DummyPolicyB("B"));

        PolicyPipeline<String, ContextPolicy<String>> pipeline = new PolicyPipeline<>(engine);

        List<String> logs = new ArrayList<>();

        // 启动流水线
        pipeline.executeChain("context", (policy, context) -> {
            logs.add(((DummyPolicy) policy).getName());
            return true; // 告知继续执行链路响应
        });

        Assert.assertEquals("应当执行完成链条上面所有受支持触发两端", 2, logs.size());
        Assert.assertTrue("预期包含A的轨迹", logs.contains("A"));
        Assert.assertTrue("预期包含B的轨迹记录", logs.contains("B"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testExecuteChainInterrupt() {
        OrderedPolicyChain<String, ContextPolicy<String>> engine = new OrderedPolicyChain<>(
                (Class) ContextPolicy.class);
        // 通过 priority() 让它按顺序排列 B, A (因 priority 默认是 NORMAL, 修改来控制顺序)
        engine.register(new DummyPolicyB("B", 2));
        engine.register(new DummyPolicyA("A", 1));

        PolicyPipeline<String, ContextPolicy<String>> pipeline = new PolicyPipeline<>(engine);

        List<String> logs = new ArrayList<>();

        // 启动流水线
        pipeline.executeChain("context", (policy, context) -> {
            logs.add(((DummyPolicy) policy).getName());
            // 如果跑到首个优先级的就截断告诉不要流转
            return false;
        });

        Assert.assertEquals("链路预期遇到阻断返回仅触发首次调阅一个即刻切断", 1, logs.size());
        Assert.assertEquals("因为A的priority更小所以优先排在表首面触碰即切断", "A", logs.get(0));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testExecuteChainWithEmptyChain() {
        OrderedPolicyChain<String, ContextPolicy<String>> engine = new OrderedPolicyChain<>(
                (Class) ContextPolicy.class);
        PolicyPipeline<String, ContextPolicy<String>> pipeline = new PolicyPipeline<>(engine);

        Assert.assertTrue("空链执行应直接完成", pipeline.executeChain("context", (policy, context) -> false));
    }

    @Test(expected = IllegalStateException.class)
    @SuppressWarnings("unchecked")
    public void testExecuteChainActionThrows() {
        OrderedPolicyChain<String, ContextPolicy<String>> engine = new OrderedPolicyChain<>(
                (Class) ContextPolicy.class);
        engine.register(new DummyPolicyA("A"));

        PolicyPipeline<String, ContextPolicy<String>> pipeline = new PolicyPipeline<>(engine);

        pipeline.executeChain("context", (policy, context) -> {
            throw new IllegalStateException("boom");
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testOfWithoutUncheckedCastUsage() {
        OrderedPolicyChain<String, DummyPolicyA> engine = new OrderedPolicyChain<>((Class) DummyPolicyA.class);
        engine.register(new DummyPolicyA("A"));

        PolicyPipeline<String, ContextPolicy<String>> pipeline = PolicyPipeline.of(engine);

        List<String> logs = new ArrayList<>();
        pipeline.executeChain("context", (policy, context) -> {
            logs.add(((DummyPolicy) policy).getName());
            return true;
        });

        Assert.assertEquals("of 应当正确适配子类型策略链", 1, logs.size());
        Assert.assertEquals("A", logs.get(0));
    }

    static abstract class DummyPolicy implements ContextPolicy<String> {
        private final String name;
        private final int priority;

        public DummyPolicy(String name) {
            this(name, ContextPolicy.NORMAL);
        }

        public DummyPolicy(String name, int priority) {
            this.name = name;
            this.priority = priority;
        }

        public String getName() {
            return name;
        }

        @Override
        public boolean supports(String context) {
            return true;
        }

        @Override
        public int priority() {
            return priority;
        }
    }

    static class DummyPolicyA extends DummyPolicy {
        public DummyPolicyA(String name) {
            super(name);
        }

        public DummyPolicyA(String name, int priority) {
            super(name, priority);
        }
    }

    static class DummyPolicyB extends DummyPolicy {
        public DummyPolicyB(String name) {
            super(name);
        }

        public DummyPolicyB(String name, int priority) {
            super(name, priority);
        }
    }
}
