package com.team4u.framework.log;

import com.team4u.framework.config.test.TestConfigContext;
import com.team4u.framework.log.core.LogEngine;
import com.team4u.framework.log.core.LogEvent;
import com.team4u.framework.log.pipeline.interceptor.RateLimitInterceptor;
import com.team4u.framework.log.pipeline.interceptor.TargetedDyeingInterceptor;
import com.team4u.framework.log.support.TestLogHelper;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class LogBootstrapOwnershipTest {

    private TestLogHelper helper;
    private LogEngine originalEngine;
    private TestConfigContext configContext;

    @Before
    public void setUp() {
        LogBootstrap.stop();
        originalEngine = LogEngine.getInstance();
        originalEngine.reset();
        helper = TestLogHelper.start();
        configContext = TestConfigContext.create();
        configContext.put("team4u.log.dyeing",
                "[{\"id\":\"live\",\"condition\":\"meta_action == 'Live'\",\"targetLevel\":\"DEBUG\"}]");
        configContext.put("team4u.log.finops",
                "{\"maxLogLength\":500,\"maxStringLength\":2000,\"errorLimitPerSecond\":1}");
    }

    @After
    public void tearDown() {
        LogBootstrap.stop();
        helper.stop();
        LogEngine current = LogEngine.getInstance();
        if (current != originalEngine) {
            LogEngine.restore(current, originalEngine);
        }
        configContext.destroy();
        originalEngine.reset();
    }

    @Test
    public void liveEngineResetKeepsBootstrapDyeingAndFinOpsSupplier() {
        LogBootstrap.start(LogBootstrap.Options.builder()
                .configManager(configContext.getConfigManager())
                .build());
        LogEngine governanceEngine = LogEngine.getInstance();
        RateLimitInterceptor rate = governanceEngine.getInterceptorManager()
                .getInterceptor(RateLimitInterceptor.class);

        Assert.assertTrue(rate.handle(error("same")));
        Assert.assertFalse(rate.handle(error("same")));

        LogEvent liveBeforeReset = new LogEvent().setAction("Live");
        Assert.assertTrue(governanceEngine.getInterceptorManager().execute(liveBeforeReset));
        Assert.assertEquals(org.slf4j.event.Level.DEBUG, liveBeforeReset.getLevel());

        governanceEngine.reset();

        Assert.assertEquals(LogBootstrap.State.STARTED, LogBootstrap.getState());
        Assert.assertTrue(TargetedDyeingInterceptor.getInstance().hasActiveRules());
        LogEvent liveAfterReset = new LogEvent().setAction("Live");
        Assert.assertTrue(governanceEngine.getInterceptorManager().execute(liveAfterReset));
        Assert.assertEquals(org.slf4j.event.Level.DEBUG, liveAfterReset.getLevel());

        Assert.assertTrue(rate.handle(error("same")));
        LogEvent suppressed = error("same");
        Assert.assertFalse(rate.handle(suppressed));
        Assert.assertTrue(suppressed.isSuppressed());
    }

    @Test
    public void independentEngineResetCannotAlterGovernanceRateState() {
        LogBootstrap.start(LogBootstrap.Options.builder()
                .configManager(configContext.getConfigManager())
                .build());
        LogEngine governanceEngine = LogEngine.getInstance();
        RateLimitInterceptor governanceRate = governanceEngine.getInterceptorManager()
                .getInterceptor(RateLimitInterceptor.class);
        Assert.assertTrue(governanceRate.handle(error("shared")));
        Assert.assertFalse(governanceRate.handle(error("shared")));

        LogEngine independent = LogEngine.builder().build();
        independent.getInterceptorManager()
                .getInterceptor(RateLimitInterceptor.class)
                .setErrorLimitPerSecond(() -> 100);
        independent.reset();

        LogEvent next = error("shared");
        Assert.assertFalse(governanceRate.handle(next));
        Assert.assertTrue(next.isSuppressed());
    }

    @Test
    public void bootstrapRateUsesFinOpsHotUpdateAfterCounterOnlyReset() {
        LogBootstrap.start(LogBootstrap.Options.builder()
                .configManager(configContext.getConfigManager())
                .build());
        LogEngine engine = LogEngine.getInstance();
        RateLimitInterceptor rate = engine.getInterceptorManager()
                .getInterceptor(RateLimitInterceptor.class);

        Assert.assertTrue(rate.handle(error("same")));
        Assert.assertFalse(rate.handle(error("same")));

        configContext.put("team4u.log.finops",
                "{\"maxLogLength\":500,\"maxStringLength\":2000,\"errorLimitPerSecond\":2}");
        rate.stop();

        Assert.assertTrue(rate.handle(error("same")));
        Assert.assertTrue(rate.handle(error("same")));
        LogEvent third = error("same");
        Assert.assertFalse(rate.handle(third));
        Assert.assertTrue(third.isSuppressed());
    }

    private LogEvent error(String action) {
        return new LogEvent().setLoggerName(getClass().getName()).setAction(action)
                .setException(new RuntimeException(action));
    }
}
