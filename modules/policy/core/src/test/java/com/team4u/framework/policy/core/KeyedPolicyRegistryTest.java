package com.team4u.framework.policy.core;

import com.team4u.framework.policy.api.ContextPolicy;
import com.team4u.framework.policy.api.KeyedPolicy;
import com.team4u.framework.policy.exception.PolicyException;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;

/**
 * 键值策略注册表单元测试
 */
public class KeyedPolicyRegistryTest {

    @Test

    public void testRegisterNullPolicy() {
        KeyedPolicyRegistry<String, TestPolicy> registry = new KeyedPolicyRegistry<>(TestPolicy.class);

        // 注册 null 应当静默忽略
        registry.register(null);

        Assert.assertTrue("注册 null 后应当为空", registry.getPolicies().isEmpty());
    }

    @Test(expected = PolicyException.class)
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void testRegisterTypeMismatch() {
        // 使用原始类型绕过编译检查
        KeyedPolicyRegistry registry = new KeyedPolicyRegistry(TestPolicy.class);

        // 注册错误类型应当抛出 PolicyException
        registry.register(new WrongTypePolicy());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void testRegisterTypeMismatchWithDetails() {
        KeyedPolicyRegistry registry = new KeyedPolicyRegistry(TestPolicy.class);

        try {
            registry.register(new WrongTypePolicy());
            Assert.fail("应当抛出异常");
        } catch (PolicyException e) {
            Assert.assertEquals(TestPolicy.class, e.getExpectedPolicyClass());
            Assert.assertEquals(WrongTypePolicy.class, e.getActualPolicyClass());
            Assert.assertTrue(e.getMessage().contains("type mismatch"));
        }
    }

    @Test

    public void testAddAllWithNullCollection() {
        KeyedPolicyRegistry<String, TestPolicy> registry = new KeyedPolicyRegistry<>(TestPolicy.class);

        // 添加 null 集合应当静默忽略
        registry.addAll((Collection<? extends TestPolicy>) null);

        Assert.assertTrue("添加 null 后应当为空", registry.getPolicies().isEmpty());
    }

    @Test

    public void testAddAllWithEmptyCollection() {
        KeyedPolicyRegistry<String, TestPolicy> registry = new KeyedPolicyRegistry<>(TestPolicy.class);

        // 添加空集合应当静默忽略
        registry.addAll(Collections.emptyList());

        Assert.assertTrue("添加空集合后应当为空", registry.getPolicies().isEmpty());
    }

    @Test

    public void testAddAllWithNullElement() {
        KeyedPolicyRegistry<String, TestPolicy> registry = new KeyedPolicyRegistry<>(TestPolicy.class);

        // 添加包含 null 的集合应当静默跳过 null 元素
        registry.addAll(Arrays.asList(new TestPolicyImpl("A"), null));

        Assert.assertEquals("应当只添加非 null 元素", 1, registry.getPolicies().size());
    }

    @Test(expected = PolicyException.class)
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void testAddAllTypeMismatch() {
        KeyedPolicyRegistry registry = new KeyedPolicyRegistry(TestPolicy.class);

        // 添加错误类型应当抛出 PolicyException
        registry.addAll(Collections.singletonList(new WrongTypePolicy()));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void testAddAllWithTypeMismatchDetails() {
        KeyedPolicyRegistry registry = new KeyedPolicyRegistry(TestPolicy.class);

        try {
            registry.addAll(Collections.singletonList(new WrongTypePolicy()));
            Assert.fail("应当抛出异常");
        } catch (PolicyException e) {
            Assert.assertEquals(TestPolicy.class, e.getExpectedPolicyClass());
            Assert.assertEquals(WrongTypePolicy.class, e.getActualPolicyClass());
        }
    }

    @Test(expected = PolicyException.class)
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void testAddAllUnsupportedRegistryType() {
        KeyedPolicyRegistry<String, TestPolicy> registry = new KeyedPolicyRegistry<>(TestPolicy.class);

        // 添加不支持的注册表类型应当抛出 PolicyException
        OrderedPolicyChain otherChain = new OrderedPolicyChain(ContextPolicy.class);
        registry.addAll(otherChain);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void testAddAllUnsupportedRegistryTypeWithDetails() {
        KeyedPolicyRegistry<String, TestPolicy> registry = new KeyedPolicyRegistry<>(TestPolicy.class);

        try {
            OrderedPolicyChain otherChain = new OrderedPolicyChain(ContextPolicy.class);
            registry.addAll(otherChain);
            Assert.fail("应当抛出异常");
        } catch (PolicyException e) {
            Assert.assertTrue(e.getMessage().contains("KeyedPolicyRegistry"));
            Assert.assertEquals(OrderedPolicyChain.class, e.getUnsupportedRegistryClass());
        }
    }

    @Test

    public void testAddAllSameTypeRegistry() {
        KeyedPolicyRegistry<String, TestPolicy> registry1 = new KeyedPolicyRegistry<>(TestPolicy.class);
        KeyedPolicyRegistry<String, TestPolicy> registry2 = new KeyedPolicyRegistry<>(TestPolicy.class);

        registry1.register(new TestPolicyImpl("A"));
        registry2.register(new TestPolicyImpl("B"));
        registry2.register(new TestPolicyImpl("C"));

        // 合并注册表
        registry1.addAll(registry2);

        Assert.assertEquals("合并后应当包含所有策略", 3, registry1.getPolicies().size());
    }

    @Test

    public void testUnregisterNullPolicy() {
        KeyedPolicyRegistry<String, TestPolicy> registry = new KeyedPolicyRegistry<>(TestPolicy.class);
        registry.register(new TestPolicyImpl("A"));

        // 注销 null 应当静默忽略
        registry.unregister(null);

        Assert.assertEquals("注销 null 后应当保留原策略", 1, registry.getPolicies().size());
    }

    @Test

    public void testGet() {
        KeyedPolicyRegistry<String, TestPolicy> registry = new KeyedPolicyRegistry<>(TestPolicy.class);

        TestPolicyImpl aliPay = new TestPolicyImpl("ALIPAY");
        registry.register(aliPay);
        registry.register(new TestPolicyImpl("WXPAY"));

        // 精准路由 O(1)
        Assert.assertTrue("应当成功匹配到指定的策略", registry.get("ALIPAY").isPresent());
        Assert.assertSame("匹配到的对象应与注册的相同", aliPay, registry.get("ALIPAY").get());

        // 获取不存在的 Key
        Assert.assertFalse("不应匹配不存在的 Key", registry.get("UNKNOWN").isPresent());
    }

    @Test(expected = PolicyException.class)
    public void testRegisterNullKey() {
        KeyedPolicyRegistry<String, TestPolicy> registry = new KeyedPolicyRegistry<>(TestPolicy.class);

        registry.register(new TestPolicyImpl(null));
    }

    @Test
    public void testRegisterNullKeyWithDetails() {
        KeyedPolicyRegistry<String, TestPolicy> registry = new KeyedPolicyRegistry<>(TestPolicy.class);

        try {
            registry.register(new TestPolicyImpl(null));
            Assert.fail("应当抛出异常");
        } catch (PolicyException e) {
            Assert.assertEquals(TestPolicy.class, e.getExpectedPolicyClass());
            Assert.assertTrue(e.getMessage().contains("key cannot be null"));
        }
    }

    @Test
    public void testRegisterSameKeyOverride() {
        KeyedPolicyRegistry<String, TestPolicy> registry = new KeyedPolicyRegistry<>(TestPolicy.class);
        TestPolicyImpl first = new TestPolicyImpl("A");
        TestPolicyImpl second = new TestPolicyImpl("A");

        registry.register(first);
        registry.register(second);

        Assert.assertEquals("相同 key 应当只保留一个策略", 1, registry.getPolicies().size());
        Assert.assertSame("相同 key 后注册应覆盖前注册", second, registry.get("A").get());
    }

    @Test(expected = PolicyException.class)
    public void testAddAllNullKey() {
        KeyedPolicyRegistry<String, TestPolicy> registry = new KeyedPolicyRegistry<>(TestPolicy.class);

        registry.addAll(Arrays.asList(new TestPolicyImpl("A"), new TestPolicyImpl(null)));
    }

    @Test
    public void testUnregisterByType() {
        KeyedPolicyRegistry<String, TestPolicy> registry = new KeyedPolicyRegistry<>(TestPolicy.class);
        registry.register(new TestPolicyImpl("A"));
        registry.register(new TestPolicyImpl("B"));

        // 按类型注销
        int removed = registry.unregisterByType(TestPolicyImpl.class);

        Assert.assertEquals("应当移除两个策略", 2, removed);
        Assert.assertTrue("按类型注销后应当为空", registry.getPolicies().isEmpty());
    }

    @Test
    public void testUnregisterByTypeNoMatch() {
        KeyedPolicyRegistry<String, TestPolicy> registry = new KeyedPolicyRegistry<>(TestPolicy.class);
        registry.register(new TestPolicyImpl("A"));

        // 按不匹配类型注销
        int removed = registry.unregisterByType(OtherPolicyImpl.class);

        Assert.assertEquals("不匹配时不应移除任何策略", 0, removed);
        Assert.assertEquals("注销后应当保留原策略", 1, registry.getPolicies().size());
    }

    @Test
    public void testUnregisterByTypePartialMatch() {
        KeyedPolicyRegistry<String, TestPolicy> registry = new KeyedPolicyRegistry<>(TestPolicy.class);
        registry.register(new TestPolicyImpl("A"));
        registry.register(new OtherPolicyImpl("B"));

        // 按其中一个类型注销
        int removed = registry.unregisterByType(TestPolicyImpl.class);

        Assert.assertEquals("应当移除一个策略", 1, removed);
        Assert.assertEquals("注销后应当保留一个策略", 1, registry.getPolicies().size());
        Assert.assertTrue("B 应当保留", registry.get("B").isPresent());
    }

    @Test
    public void testUnregisterIf() {
        KeyedPolicyRegistry<String, TestPolicy> registry = new KeyedPolicyRegistry<>(TestPolicy.class);
        registry.register(new TestPolicyImpl("A"));
        registry.register(new TestPolicyImpl("B"));
        registry.register(new TestPolicyImpl("C"));

        // 注销 key 为 A 的策略
        int removed = registry.unregisterIf(p -> "A".equals(p.key()));

        Assert.assertEquals("应当移除一个策略", 1, removed);
        Assert.assertEquals("注销后应当保留两个策略", 2, registry.getPolicies().size());
        Assert.assertFalse("A 应当被移除", registry.get("A").isPresent());
    }

    @Test
    public void testUnregisterIfMultipleSameType() {
        KeyedPolicyRegistry<String, TestPolicy> registry = new KeyedPolicyRegistry<>(TestPolicy.class);
        registry.register(new TestPolicyImpl("A"));
        registry.register(new TestPolicyImpl("B"));
        registry.register(new OtherPolicyImpl("C"));

        int removed = registry.unregisterIf(p -> p.getClass().equals(TestPolicyImpl.class));

        Assert.assertEquals("应当移除两个同类型策略", 2, removed);
        Assert.assertFalse("A 应当被移除", registry.get("A").isPresent());
        Assert.assertFalse("B 应当被移除", registry.get("B").isPresent());
        Assert.assertTrue("其他类型策略应保留", registry.get("C").isPresent());
    }

    @Test
    public void testUnregisterAll() {
        KeyedPolicyRegistry<String, TestPolicy> registry = new KeyedPolicyRegistry<>(TestPolicy.class);
        registry.register(new TestPolicyImpl("A"));
        registry.register(new TestPolicyImpl("B"));

        registry.unregisterAll();

        Assert.assertTrue("清空后应当为空", registry.getPolicies().isEmpty());
    }

    @Test
    public void testUnregisterSuccess() {
        KeyedPolicyRegistry<String, TestPolicy> registry = new KeyedPolicyRegistry<>(TestPolicy.class);
        TestPolicyImpl a = new TestPolicyImpl("A");
        registry.register(a);

        registry.unregister(a);

        Assert.assertTrue("注销成功后应当为空", registry.getPolicies().isEmpty());
    }

    @Test
    public void testGetWithNullKey() {
        KeyedPolicyRegistry<String, TestPolicy> registry = new KeyedPolicyRegistry<>(TestPolicy.class);
        Assert.assertFalse("Key 为 null 时应返回 empty", registry.get(null).isPresent());
    }

    @Test(expected = UnsupportedOperationException.class)
    public void testGetPoliciesIsUnmodifiable() {
        KeyedPolicyRegistry<String, TestPolicy> registry = new KeyedPolicyRegistry<>(TestPolicy.class);
        registry.register(new TestPolicyImpl("A"));

        registry.getPolicies().clear();
    }

    @Test
    public void testAddAllSameTypeRegistryEmpty() {
        KeyedPolicyRegistry<String, TestPolicy> registry1 = new KeyedPolicyRegistry<>(TestPolicy.class);
        KeyedPolicyRegistry<String, TestPolicy> registry2 = new KeyedPolicyRegistry<>(TestPolicy.class);

        registry1.register(new TestPolicyImpl("A"));

        // 合并空注册表
        registry1.addAll(registry2);

        Assert.assertEquals("合并空注册表后数量不应变化", 1, registry1.getPolicies().size());
    }

    @Test
    public void testErrorCodePolicy() {
        KeyedPolicyRegistry<String, TestPolicy> registry = new KeyedPolicyRegistry<>(TestPolicy.class);

        registry.register(new TestPolicyImpl("E001"));

        Assert.assertTrue("应当成功匹配到指定的错误码策略", registry.get("E001").isPresent());
        Assert.assertEquals("匹配到的 Key 应当正确", "E001", registry.get("E001").get().key());
    }

    // --- 模拟测试类 ---

    interface TestPolicy extends KeyedPolicy<String> {
    }

    static class TestPolicyImpl implements TestPolicy {
        private final String key;

        public TestPolicyImpl(String key) {
            this.key = key;
        }

        @Override
        public String key() {
            return key;
        }
    }

    static class WrongTypePolicy implements KeyedPolicy<String> {
        @Override
        public String key() {
            return "WRONG";
        }
    }

    static class OtherPolicyImpl implements TestPolicy {
        private final String key;

        public OtherPolicyImpl(String key) {
            this.key = key;
        }

        @Override
        public String key() {
            return key;
        }
    }
}
