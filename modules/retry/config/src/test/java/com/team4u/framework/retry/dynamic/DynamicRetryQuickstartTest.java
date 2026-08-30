package com.team4u.framework.retry.dynamic;

import com.team4u.framework.config.core.support.ConfigDrivenRegistry;
import com.team4u.framework.config.test.TestConfigContext;
import com.team4u.framework.retry.api.RetryPolicy;
import com.team4u.framework.retry.config.RetryPolicyParser;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class DynamicRetryQuickstartTest {

    private TestConfigContext context;

    @Before
    public void setUp() {
        context = TestConfigContext.create();
        DynamicRetryPolicyRegistry.setRegistry(new ConfigDrivenRegistry<>(
                context.getConfigManager(),
                "retry.policy.*",
                RetryPolicyParser::create));
    }

    @After
    public void tearDown() {
        if (context != null) {
            context.destroy();
        }
        DynamicRetryPolicyRegistry.reset();
    }

    @Test
    public void dynamicPolicyHotUpdateChangesRetryBudgetWithoutRestart() {
        String configKey = "retry.policy.quickstart";

        context.put(configKey,
                "{\"maxRetries\":1,\"backoff\":{\"type\":\"fixed\",\"params\":{\"delay\":1}}}");
        RetryPolicy before = DynamicRetryPolicyRegistry.getPolicy("quickstart");
        Assert.assertNotNull(before);
        Assert.assertTrue(before.canRetry(1, new RuntimeException("first failure")));
        Assert.assertFalse(before.canRetry(2, new RuntimeException("second failure")));

        context.put(configKey,
                "{\"maxRetries\":4,\"backoff\":{\"type\":\"fixed\",\"params\":{\"delay\":1}}}");
        RetryPolicy after = DynamicRetryPolicyRegistry.getPolicy("quickstart");

        Assert.assertNotSame(before, after);
        Assert.assertTrue(after.canRetry(2, new RuntimeException("second failure")));
        Assert.assertTrue(after.canRetry(4, new RuntimeException("fourth failure")));
        Assert.assertFalse(after.canRetry(5, new RuntimeException("fifth failure")));
    }
}
