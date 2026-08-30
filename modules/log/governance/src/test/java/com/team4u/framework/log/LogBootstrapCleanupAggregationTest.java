package com.team4u.framework.log;

import com.team4u.framework.config.core.ConfigManager;
import com.team4u.framework.config.test.TestConfigContext;
import com.team4u.framework.log.config.FinOpsConfigRepository;
import com.team4u.framework.log.core.LogEngine;
import com.team4u.framework.log.pipeline.interceptor.FirstCleanupTestInterceptor;
import com.team4u.framework.log.pipeline.interceptor.SecondCleanupTestInterceptor;
import com.team4u.framework.log.pipeline.interceptor.TargetedDyeingInterceptor;
import com.team4u.framework.log.proxy.ProxyRuleRepository;
import com.team4u.framework.log.support.TestLogHelper;
import com.team4u.framework.mask.MaskRuleResolver;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class LogBootstrapCleanupAggregationTest {

    private LogEngine originalEngine;
    private TestLogHelper helper;

    @Before
    public void setUp() {
        LogBootstrap.stop();
        originalEngine = LogEngine.getInstance();
        originalEngine.reset();
        helper = TestLogHelper.start();
    }

    @After
    public void tearDown() {
        FirstCleanupTestInterceptor.failStop = false;
        SecondCleanupTestInterceptor.failStop = false;
        LogBootstrap.stop();
        helper.stop();
        LogEngine current = LogEngine.getInstance();
        if (current != originalEngine) {
            LogEngine.restore(current, originalEngine);
        }
        originalEngine.reset();
    }

    @Test
    public void stopAggregatesRepositoryAndDetachedEngineResetFailures() {
        TestConfigContext configContext = TestConfigContext.create();
        try {
            LogBootstrap.start(LogBootstrap.Options.builder()
                    .configManager(configContext.getConfigManager())
                    .build());
            LogEngine governanceEngine = LogEngine.getInstance();
            LogEngine externalEngine = LogEngine.builder().build();
            LogEngine.install(externalEngine);

            FirstCleanupTestInterceptor.failStop = true;
            SecondCleanupTestInterceptor.failStop = true;
            RuntimeException error = null;
            try {
                LogBootstrap.stop();
            } catch (RuntimeException runtimeError) {
                error = runtimeError;
            }

            Assert.assertNotNull("Expected cleanup failure", error);
            Assert.assertEquals("test-spi-first", error.getMessage());
            Assert.assertEquals(1, error.getSuppressed().length);
            Assert.assertEquals("test-spi-second", error.getSuppressed()[0].getMessage());
            Assert.assertEquals(LogBootstrap.State.FAILED, LogBootstrap.getState());
            Assert.assertNull(LogBootstrap.getInstalledEngineForTest());
            Assert.assertNull(LogBootstrap.getPreviousEngineForTest());
            Assert.assertSame(externalEngine, LogEngine.getInstance());
            Assert.assertNotSame(governanceEngine, externalEngine);
            assertRepositoriesStopped();
        } finally {
            FirstCleanupTestInterceptor.failStop = false;
            SecondCleanupTestInterceptor.failStop = false;
            configContext.destroy();
        }
    }

    @Test
    public void failedStartAggregatesRepositoryAndEngineCleanupFailures() {
        FirstCleanupTestInterceptor.failStop = true;
        SecondCleanupTestInterceptor.failStop = true;
        try {
            RuntimeException error = null;
            try {
                LogBootstrap.start(LogBootstrap.Options.builder()
                        .configManager(FailingStartConfigManager.INSTANCE)
                        .build());
            } catch (RuntimeException runtimeError) {
                error = runtimeError;
            }

            Assert.assertNotNull("Expected start failure", error);
            Assert.assertEquals("boom-start", error.getMessage());
            Assert.assertEquals(1, error.getSuppressed().length);
            Assert.assertEquals(
                    "test-spi-first",
                    error.getSuppressed()[0].getMessage());
            Assert.assertEquals(
                    "test-spi-second",
                    error.getSuppressed()[0].getSuppressed()[0].getMessage());
            Assert.assertEquals(LogBootstrap.State.FAILED, LogBootstrap.getState());
            Assert.assertNull(LogBootstrap.getInstalledEngineForTest());
            Assert.assertNull(LogBootstrap.getPreviousEngineForTest());
            Assert.assertSame(originalEngine, LogEngine.getInstance());
            assertRepositoriesStopped();
        } finally {
            FirstCleanupTestInterceptor.failStop = false;
            SecondCleanupTestInterceptor.failStop = false;
        }
    }

    private void assertRepositoriesStopped() {
        Assert.assertFalse(TargetedDyeingInterceptor.getInstance().hasActiveRules());
        Assert.assertNull(ProxyRuleRepository.getInstance().getRule(getClass().getName()));
        FinOpsConfigRepository.FinOpsConfig defaults = FinOpsConfigRepository.FinOpsConfig.defaults();
        Assert.assertEquals(defaults.getErrorLimitPerSecond(),
                FinOpsConfigRepository.getInstance().get().getErrorLimitPerSecond());
        Assert.assertSame(MaskRuleResolver.NO_OP, MaskRuleResolver.Global.get());
    }

    private enum FailingStartConfigManager implements ConfigManager {
        INSTANCE;

        @Override
        public com.team4u.framework.config.core.domain.ConfigSnapshot currentSnapshot() {
            throw new IllegalStateException("boom-start");
        }

        @Override
        public <T> T createProxy(String prefix, Class<T> configType) {
            throw new IllegalStateException("boom-start");
        }

        @Override
        public AutoCloseable registerChangeListener(String keyPattern,
                com.team4u.framework.config.core.ConfigChangeListener listener) {
            throw new IllegalStateException("boom-start");
        }
    }
}
