package com.team4u.framework.policy.exception;

import com.team4u.framework.policy.api.KeyedPolicy;
import com.team4u.framework.policy.core.KeyedPolicyRegistry;
import com.team4u.framework.policy.core.OrderedPolicyChain;
import org.junit.Assert;
import org.junit.Test;

/**
 * 策略异常单元测试
 */
public class PolicyExceptionTest {

    @Test
    public void testTypeMismatchWithContext() {
        Class<StringPolicy> expectedClass = StringPolicy.class;
        Class<IntegerPolicy> actualClass = IntegerPolicy.class;

        PolicyException exception = PolicyException.typeMismatch(expectedClass, actualClass);

        Assert.assertEquals("消息应包含类型信息",
                "Policy type mismatch, expected: com.team4u.framework.policy.exception.PolicyExceptionTest$StringPolicy, got: com.team4u.framework.policy.exception.PolicyExceptionTest$IntegerPolicy",
                exception.getMessage());
        Assert.assertEquals(expectedClass, exception.getExpectedPolicyClass());
        Assert.assertEquals(actualClass, exception.getActualPolicyClass());
        Assert.assertNull(exception.getPolicyKey());
        Assert.assertNull(exception.getUnsupportedRegistryClass());
    }

    @SuppressWarnings("rawtypes")
    @Test
    public void testUnsupportedRegistryWithContext() {
        Class<KeyedPolicyRegistry> expectedClass = KeyedPolicyRegistry.class;
        Class<OrderedPolicyChain> actualClass = OrderedPolicyChain.class;

        PolicyException exception = PolicyException.unsupportedRegistry(expectedClass, actualClass);

        Assert.assertEquals("消息应包含类型信息",
                "Only KeyedPolicyRegistry is supported, got: com.team4u.framework.policy.core.OrderedPolicyChain",
                exception.getMessage());
        Assert.assertNull(exception.getExpectedPolicyClass());
        Assert.assertNull(exception.getActualPolicyClass());
        Assert.assertNull(exception.getPolicyKey());
        Assert.assertEquals(actualClass, exception.getUnsupportedRegistryClass());
    }

    @Test
    public void testPolicyNull() {
        PolicyException exception = PolicyException.policyNull();

        Assert.assertEquals("Policy cannot be null", exception.getMessage());
        Assert.assertNull(exception.getExpectedPolicyClass());
        Assert.assertNull(exception.getActualPolicyClass());
        Assert.assertNull(exception.getPolicyKey());
    }

    @Test
    public void testPolicyKeyNull() {
        Class<StringPolicy> policyClass = StringPolicy.class;

        PolicyException exception = PolicyException.policyKeyNull(policyClass);

        Assert.assertEquals("消息应包含策略类型",
                "Policy key cannot be null for policy type: com.team4u.framework.policy.exception.PolicyExceptionTest$StringPolicy",
                exception.getMessage());
        Assert.assertEquals(policyClass, exception.getExpectedPolicyClass());
    }

    @Test
    public void testBuilder() {
        PolicyException exception = PolicyException.builder()
                .message("Custom error message")
                .expectedPolicyClass(StringPolicy.class)
                .actualPolicyClass(IntegerPolicy.class)
                .policyKey("TEST_KEY")
                .cause(new RuntimeException("Root cause"))
                .build();

        Assert.assertEquals("Custom error message", exception.getMessage());
        Assert.assertEquals(StringPolicy.class, exception.getExpectedPolicyClass());
        Assert.assertEquals(IntegerPolicy.class, exception.getActualPolicyClass());
        Assert.assertEquals("TEST_KEY", exception.getPolicyKey());
        Assert.assertNotNull(exception.getCause());
        Assert.assertEquals("Root cause", exception.getCause().getMessage());
    }

    // --- 模拟测试类 ---

    interface StringPolicy extends KeyedPolicy<String> {
    }

    interface IntegerPolicy extends KeyedPolicy<Integer> {
    }
}
