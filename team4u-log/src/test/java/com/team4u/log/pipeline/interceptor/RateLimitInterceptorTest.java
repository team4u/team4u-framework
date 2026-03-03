package com.team4u.log.pipeline.interceptor;

import com.team4u.log.config.LogConfigManager;
import com.team4u.log.config.LogDynamicConfig;
import com.team4u.log.core.LogEvent;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * 异常限流拦截器单元测试
 */
public class RateLimitInterceptorTest {

    private RateLimitInterceptor interceptor;

    @Before
    public void setup() {
        interceptor = RateLimitInterceptor.getInstance();
        interceptor.reset();
        // 将阈值调低方便测试
        updateLimit(2);
    }

    private void updateLimit(int limit) {
        LogDynamicConfig config = new LogDynamicConfig();
        LogDynamicConfig.FinOpsConfig finOpsConfig = new LogDynamicConfig.FinOpsConfig();
        finOpsConfig.setErrorLimitPerSecond(limit);
        config.setFinOpsConfig(finOpsConfig);
        LogConfigManager.getInstance().setCurrentConfig(config);
    }

    @Test
    public void testRateLimiting() {
        LogEvent event1 = createErrorEvent("ActionA", new RuntimeException("E1"));
        LogEvent event2 = createErrorEvent("ActionA", new RuntimeException("E1"));
        LogEvent event3 = createErrorEvent("ActionA", new RuntimeException("E1"));

        // 前两次正常
        Assert.assertTrue("第1条应通过", interceptor.handle(event1));
        Assert.assertFalse("第1条不应被抑制", event1.isSuppressed());

        Assert.assertTrue("第2条应通过", interceptor.handle(event2));
        Assert.assertFalse("第2条不应被抑制", event2.isSuppressed());

        // 第三次被拦截
        Assert.assertFalse("第3条应被拦截", interceptor.handle(event3));
        Assert.assertTrue("第3条应被抑制", event3.isSuppressed());
    }

    @Test
    public void testDifferentExceptions() {
        LogEvent event1 = createErrorEvent("ActionA", new RuntimeException("E1"));
        LogEvent event2 = createErrorEvent("ActionA", new IllegalArgumentException("E2"));

        Assert.assertTrue(interceptor.handle(event1));
        Assert.assertTrue(interceptor.handle(event2));
        // 不同的异常特征不影响彼此计数
    }

    @Test
    public void testNonErrorEvent() {
        LogEvent event = new LogEvent().setAction("ActionA");
        Assert.assertTrue("非异常日志不应被限流", interceptor.handle(event));
    }

    @Test
    public void testResetAndThresholdUpdate() {
        updateLimit(1);
        interceptor.handle(createErrorEvent("X", new RuntimeException()));
        Assert.assertFalse("第2条本应被拦截", interceptor.handle(createErrorEvent("X", new RuntimeException())));

        // 刷新限流并重置
        updateLimit(10);
        interceptor.reset();

        Assert.assertTrue("重置后应恢复正常", interceptor.handle(createErrorEvent("X", new RuntimeException())));
    }

    @Test
    public void testPriority() {
        Assert.assertEquals(1000, interceptor.priority());
    }

    private LogEvent createErrorEvent(String action, Throwable e) {
        return new LogEvent().setAction(action).setException(e);
    }
}
