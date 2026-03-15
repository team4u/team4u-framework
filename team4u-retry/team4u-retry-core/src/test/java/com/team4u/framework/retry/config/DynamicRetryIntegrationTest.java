package com.team4u.framework.retry.config;

import com.team4u.framework.config.core.support.ConfigDrivenRegistry;
import com.team4u.framework.config.test.TestConfigContext;
import com.team4u.framework.retry.api.RetryPolicy;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * 动态配置热重载集成测试
 *
 * @author jay.wu
 */
public class DynamicRetryIntegrationTest {

    private TestConfigContext context;

    @Before
    public void setUp() {
        context = TestConfigContext.create();
        // 注入 TestConfigContext 中的 ConfigManager 到注册表中，实现零延迟同步重载测试
        DynamicRetryPolicyRegistry.setRegistry(new ConfigDrivenRegistry<>(
                context.getConfigManager(),
                "retry.policy.",
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
    public void testDynamicPolicyUpdate() {
        String policyId = "test-dynamic-policy";
        String configKey = "retry.policy." + policyId;

        // 1. 下发初始配置：失败后最多重试 1 次，总共执行 2 次
        context.put(configKey,
                "{\"maxRetries\": 1, \"backoff\": {\"type\": \"fixed\", \"params\": {\"delay\": 100}}}");

        RetryPolicy policy1 = DynamicRetryPolicyRegistry.getPolicy(policyId);
        Assert.assertNotNull(policy1);
        Assert.assertTrue("初始配置应允许第1次重试", policy1.canRetry(1, new RuntimeException()));
        Assert.assertFalse("初始配置不应允许第2次重试", policy1.canRetry(2, new RuntimeException()));

        // 2. 动态修改配置：失败后最多重试 4 次，总共执行 5 次
        context.put(configKey,
                "{\"maxRetries\": 4, \"backoff\": {\"type\": \"fixed\", \"params\": {\"delay\": 200}}}");

        RetryPolicy policy2 = DynamicRetryPolicyRegistry.getPolicy(policyId);
        Assert.assertNotSame("预期热更新产生了新的策略实例", policy1, policy2);
        Assert.assertTrue("热更新后应允许第2次重试", policy2.canRetry(2, new RuntimeException()));
    }
}
