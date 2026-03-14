package com.team4u.framework.policy.core;

import com.team4u.framework.policy.api.ContextPolicy;
import com.team4u.framework.policy.api.KeyedPolicy;
import com.team4u.framework.policy.exception.PolicyException;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 有序策略链单元测试
 */
public class OrderedPolicyChainTest {

    @Test
    public void testRegisterNullPolicy() {
        OrderedPolicyChain<String, TestPolicy> chain = new OrderedPolicyChain<>(TestPolicy.class);

        // 注册 null 应当静默忽略
        chain.register(null);

        Assert.assertTrue("注册 null 后应当为空", chain.getPolicies().isEmpty());
    }

    @Test(expected = PolicyException.class)
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void testRegisterTypeMismatch() {
        OrderedPolicyChain chain = new OrderedPolicyChain(TestPolicy.class);

        // 注册错误类型应当抛出 PolicyException
        chain.register(new WrongTypePolicy());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void testRegisterTypeMismatchWithDetails() {
        OrderedPolicyChain chain = new OrderedPolicyChain(TestPolicy.class);

        try {
            chain.register(new WrongTypePolicy());
            Assert.fail("应当抛出异常");
        } catch (PolicyException e) {
            Assert.assertEquals(TestPolicy.class, e.getExpectedPolicyClass());
            Assert.assertEquals(WrongTypePolicy.class, e.getActualPolicyClass());
            Assert.assertTrue(e.getMessage().contains("type mismatch"));
        }
    }

    @Test
    public void testAddAllWithNullCollection() {
        OrderedPolicyChain<String, TestPolicy> chain = new OrderedPolicyChain<>(TestPolicy.class);

        // 添加 null 集合应当静默忽略
        chain.addAll((java.util.Collection<? extends TestPolicy>) null);

        Assert.assertTrue("添加 null 后应当为空", chain.getPolicies().isEmpty());
    }

    @Test
    public void testAddAllWithEmptyCollection() {
        OrderedPolicyChain<String, TestPolicy> chain = new OrderedPolicyChain<>(TestPolicy.class);

        // 添加空集合应当静默忽略
        chain.addAll(Collections.emptyList());

        Assert.assertTrue("添加空集合后应当为空", chain.getPolicies().isEmpty());
    }

    @Test
    public void testAddAllWithNullElement() {
        OrderedPolicyChain<String, TestPolicy> chain = new OrderedPolicyChain<>(TestPolicy.class);

        // 添加包含 null 的集合应当跳过 null 元素
        chain.addAll(Arrays.asList(new DummyPolicy(1), null));

        Assert.assertEquals("应当只添加非 null 元素", 1, chain.getPolicies().size());
    }

    @Test(expected = PolicyException.class)
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void testAddAllTypeMismatch() {
        OrderedPolicyChain chain = new OrderedPolicyChain(TestPolicy.class);

        // 添加错误类型应当抛出 PolicyException
        chain.addAll(Collections.singletonList(new WrongTypePolicy()));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void testAddAllWithTypeMismatchDetails() {
        OrderedPolicyChain chain = new OrderedPolicyChain(TestPolicy.class);

        try {
            chain.addAll(Collections.singletonList(new WrongTypePolicy()));
            Assert.fail("应当抛出异常");
        } catch (PolicyException e) {
            Assert.assertEquals(TestPolicy.class, e.getExpectedPolicyClass());
            Assert.assertEquals(WrongTypePolicy.class, e.getActualPolicyClass());
        }
    }

    @Test(expected = PolicyException.class)
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void testAddAllUnsupportedRegistryType() {
        OrderedPolicyChain<String, TestPolicy> chain = new OrderedPolicyChain<>(TestPolicy.class);

        // 添加不支持的注册表类型应当抛出 PolicyException
        KeyedPolicyRegistry otherRegistry = new KeyedPolicyRegistry(KeyedPolicy.class);
        chain.addAll(otherRegistry);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void testAddAllUnsupportedRegistryTypeWithDetails() {
        OrderedPolicyChain<String, TestPolicy> chain = new OrderedPolicyChain<>(TestPolicy.class);

        try {
            KeyedPolicyRegistry otherRegistry = new KeyedPolicyRegistry(KeyedPolicy.class);
            chain.addAll(otherRegistry);
            Assert.fail("应当抛出异常");
        } catch (PolicyException e) {
            Assert.assertTrue(e.getMessage().contains("OrderedPolicyChain"));
            Assert.assertEquals(KeyedPolicyRegistry.class, e.getUnsupportedRegistryClass());
        }
    }

    @Test

    public void testAddAllSameTypeRegistry() {
        OrderedPolicyChain<String, TestPolicy> chain1 = new OrderedPolicyChain<>(TestPolicy.class);
        OrderedPolicyChain<String, TestPolicy> chain2 = new OrderedPolicyChain<>(TestPolicy.class);

        // 使用不同的实现类避免替换
        chain1.register(new DummyPolicyA(2));
        chain2.register(new DummyPolicyB(1));

        // 合并注册表
        chain1.addAll(chain2);

        Assert.assertEquals("合并后应当包含所有策略", 2, chain1.getPolicies().size());
    }

    @Test

    public void testUnregisterNullPolicy() {
        OrderedPolicyChain<String, TestPolicy> chain = new OrderedPolicyChain<>(TestPolicy.class);
        chain.register(new DummyPolicy(1));

        // 注销 null 应当静默忽略
        chain.unregister(null);

        Assert.assertEquals("注销 null 后应当保留原策略", 1, chain.getPolicies().size());
    }

    @Test

    public void testSortingAndMatch() {
        OrderedPolicyChain<String, TestPolicy> chain = new OrderedPolicyChain<>(TestPolicy.class);

        // 注册不同优先级的策略 (使用不同实现类避免被替换)
        chain.register(new DummyPolicyA(10));
        chain.register(new DummyPolicyB(5));
        chain.register(new DummyPolicyC(20));

        // 验证排序结果
        List<TestPolicy> policies = chain.getPolicies();
        Assert.assertEquals("应当包含所有注册策略", 3, policies.size());
        Assert.assertEquals("排序第一应当是 B", 5, policies.get(0).priority());
        Assert.assertEquals("排序第二应当是 A", 10, policies.get(1).priority());
        Assert.assertEquals("排序第三应当是 C", 20, policies.get(2).priority());

        // 验证首个匹配
        String context = "ANY";
        Optional<TestPolicy> first = chain.firstMatch(context);
        Assert.assertTrue(first.isPresent());
        Assert.assertEquals("首个匹配应当是优先级最高的 B", 5, first.get().priority());

        // 验证全量匹配
        Assert.assertEquals("全量匹配应当返回 3 个", 3, chain.allMatches(context).size());
    }

    @Test
    public void testRegisterAppendByDefault() {
        OrderedPolicyChain<String, TestPolicy> chain = new OrderedPolicyChain<>(TestPolicy.class);

        // 默认模式为追加，同类策略应共存
        chain.register(new DummyPolicyA(10));
        chain.register(new DummyPolicyA(20));

        Assert.assertEquals("默认模式下同类策略应当共存", 2, chain.getPolicies().size());
        Assert.assertEquals("优先级更高的应排在前面", 10, chain.getPolicies().get(0).priority());
        Assert.assertEquals("后注册的低优先级策略应排在后面", 20, chain.getPolicies().get(1).priority());
    }

    @Test
    public void testRegisterReplaceByClassMode() {
        OrderedPolicyChain<String, TestPolicy> chain = new OrderedPolicyChain<>(
                TestPolicy.class, DuplicatePolicyMode.REPLACE_BY_CLASS);

        chain.register(new DummyPolicyA(10));
        chain.register(new DummyPolicyA(20));

        Assert.assertEquals("替换模式下同类策略应当只保留一个", 1, chain.getPolicies().size());
        Assert.assertEquals("应当保留的是后注册的", 20, chain.getPolicies().get(0).priority());
    }

    @Test
    public void testSamePriorityKeepRegistrationOrder() {
        OrderedPolicyChain<String, TestPolicy> chain = new OrderedPolicyChain<>(TestPolicy.class);

        DummyPolicyA first = new DummyPolicyA(10);
        DummyPolicyB second = new DummyPolicyB(10);
        DummyPolicyC third = new DummyPolicyC(10);

        chain.register(first);
        chain.register(second);
        chain.register(third);

        Assert.assertSame("相同优先级时应保留注册顺序", first, chain.getPolicies().get(0));
        Assert.assertSame("相同优先级时应保留注册顺序", second, chain.getPolicies().get(1));
        Assert.assertSame("相同优先级时应保留注册顺序", third, chain.getPolicies().get(2));
    }

    @Test
    public void testReplaceModeReRegisterChangesOrderWithinSamePriority() {
        OrderedPolicyChain<String, TestPolicy> chain = new OrderedPolicyChain<>(
                TestPolicy.class, DuplicatePolicyMode.REPLACE_BY_CLASS);

        DummyPolicyA first = new DummyPolicyA(10);
        DummyPolicyB second = new DummyPolicyB(10);
        DummyPolicyA replacement = new DummyPolicyA(10);

        chain.register(first);
        chain.register(second);
        chain.register(replacement);

        Assert.assertSame("重新注册后应视为新的注册时刻", second, chain.getPolicies().get(0));
        Assert.assertSame("替换后的实例应排到同优先级末尾", replacement, chain.getPolicies().get(1));
    }

    @Test
    public void testAddAllReplaceByClassMode() {
        OrderedPolicyChain<String, TestPolicy> chain = new OrderedPolicyChain<>(
                TestPolicy.class, DuplicatePolicyMode.REPLACE_BY_CLASS);

        chain.register(new DummyPolicyA(10));
        chain.addAll(Arrays.asList(new DummyPolicyA(20), new DummyPolicyB(5)));

        Assert.assertEquals("替换模式下同类 addAll 后应只保留一份", 2, chain.getPolicies().size());
        Assert.assertEquals("新增的高优先级策略应排在前面", DummyPolicyB.class, chain.getPolicies().get(0).getClass());
        Assert.assertEquals("同类策略应保留批量注册中的最新实例", 20, chain.getPolicies().get(1).priority());
    }

    @Test
    public void testAddAllRegistryReplaceByClassMode() {
        OrderedPolicyChain<String, TestPolicy> chain1 = new OrderedPolicyChain<>(
                TestPolicy.class, DuplicatePolicyMode.REPLACE_BY_CLASS);
        OrderedPolicyChain<String, TestPolicy> chain2 = new OrderedPolicyChain<>(
                TestPolicy.class, DuplicatePolicyMode.REPLACE_BY_CLASS);

        chain1.register(new DummyPolicyA(10));
        chain2.register(new DummyPolicyA(20));
        chain2.register(new DummyPolicyB(5));

        chain1.addAll(chain2);

        Assert.assertEquals("合并 registry 后同类策略应保持替换语义", 2, chain1.getPolicies().size());
        Assert.assertEquals(DummyPolicyB.class, chain1.getPolicies().get(0).getClass());
        Assert.assertEquals(20, chain1.getPolicies().get(1).priority());
    }

    @Test
    public void testUnregisterByType() {
        OrderedPolicyChain<String, TestPolicy> chain = new OrderedPolicyChain<>(TestPolicy.class);
        chain.register(new DummyPolicyA(1));
        chain.register(new DummyPolicyB(2));

        int removed = chain.unregisterByType(DummyPolicyA.class);

        Assert.assertEquals("应当移除一个策略", 1, removed);
        Assert.assertEquals("移除后应当只剩一个", 1, chain.getPolicies().size());
        Assert.assertEquals("保留的应当是 B", DummyPolicyB.class, chain.getPolicies().get(0).getClass());
    }

    @Test
    public void testUnregisterIf() {
        OrderedPolicyChain<String, TestPolicy> chain = new OrderedPolicyChain<>(TestPolicy.class);
        chain.register(new DummyPolicyA(1));
        chain.register(new DummyPolicyB(2));

        int removed = chain.unregisterIf(p -> p.priority() == 1);

        Assert.assertEquals("应当移除一个策略", 1, removed);
        Assert.assertEquals("移除后应当只剩一个", 1, chain.getPolicies().size());
    }

    @Test
    public void testUnregisterAll() {
        OrderedPolicyChain<String, TestPolicy> chain = new OrderedPolicyChain<>(TestPolicy.class);
        chain.register(new DummyPolicyA(1));
        chain.unregisterAll();

        Assert.assertTrue("清空后应当为空", chain.getPolicies().isEmpty());
    }

    @Test
    public void testUnregisterSuccess() {
        OrderedPolicyChain<String, TestPolicy> chain = new OrderedPolicyChain<>(TestPolicy.class);
        DummyPolicyA a = new DummyPolicyA(1);
        chain.register(a);

        chain.unregister(a);

        Assert.assertTrue("注销成功后应当为空", chain.getPolicies().isEmpty());
    }

    @Test
    public void testFirstMatchNoMatch() {
        OrderedPolicyChain<String, TestPolicy> chain = new OrderedPolicyChain<>(TestPolicy.class);
        chain.register(new TestPolicy() {
            @Override
            public boolean supports(String context) {
                return false;
            }

            @Override
            public int priority() {
                return 0;
            }
        });

        Assert.assertFalse("不匹配时应返回 empty", chain.firstMatch("ANY").isPresent());
    }

    @Test
    public void testFirstMatchWithNullContext() {
        OrderedPolicyChain<String, TestPolicy> chain = new OrderedPolicyChain<>(TestPolicy.class);
        Assert.assertFalse("Context 为 null 时匹配应正常处理（取决于策略实现）", chain.firstMatch(null).isPresent());
    }

    @Test
    public void testAllMatchesNoMatch() {
        OrderedPolicyChain<String, TestPolicy> chain = new OrderedPolicyChain<>(TestPolicy.class);
        Assert.assertTrue("无匹配时应返回空列表", chain.allMatches("ANY").isEmpty());
    }

    @Test(expected = UnsupportedOperationException.class)
    public void testGetPoliciesIsUnmodifiable() {
        OrderedPolicyChain<String, TestPolicy> chain = new OrderedPolicyChain<>(TestPolicy.class);
        chain.register(new DummyPolicyA(1));

        chain.getPolicies().clear();
    }

    @Test
    public void testAddAllSameTypeRegistryEmpty() {
        OrderedPolicyChain<String, TestPolicy> chain1 = new OrderedPolicyChain<>(TestPolicy.class);
        OrderedPolicyChain<String, TestPolicy> chain2 = new OrderedPolicyChain<>(TestPolicy.class);

        chain1.register(new DummyPolicyA(1));

        // 合并空注册表
        chain1.addAll(chain2);

        Assert.assertEquals("合并空注册表后数量不应变化", 1, chain1.getPolicies().size());
    }

    // --- 模拟测试类 ---

    interface TestPolicy extends ContextPolicy<String> {
    }

    static class DummyPolicy implements TestPolicy {
        private final int priority;

        public DummyPolicy(int priority) {
            this.priority = priority;
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

    static class DummyPolicyA implements TestPolicy {
        private final int priority;

        public DummyPolicyA(int priority) {
            this.priority = priority;
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

    static class DummyPolicyB implements TestPolicy {
        private final int priority;

        public DummyPolicyB(int priority) {
            this.priority = priority;
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

    static class DummyPolicyC implements TestPolicy {
        private final int priority;

        public DummyPolicyC(int priority) {
            this.priority = priority;
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

    static class WrongTypePolicy implements ContextPolicy<String> {
        @Override
        public boolean supports(String context) {
            return false;
        }

        @Override
        public int priority() {
            return 0;
        }
    }
}
