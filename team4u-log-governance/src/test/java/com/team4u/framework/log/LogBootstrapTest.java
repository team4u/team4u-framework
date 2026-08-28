package com.team4u.framework.log;

import com.team4u.framework.config.core.ConfigChangeListener;
import com.team4u.framework.config.core.ConfigManager;
import com.team4u.framework.config.core.domain.ConfigSnapshot;
import com.team4u.framework.config.test.TestConfigContext;
import com.team4u.framework.log.core.LogEngine;
import com.team4u.framework.log.pipeline.interceptor.RateLimitInterceptor;
import com.team4u.framework.log.pipeline.interceptor.TargetedDyeingInterceptor;
import com.team4u.framework.log.proxy.ProxyRuleRepository;
import com.team4u.framework.log.core.LogEvent;
import com.team4u.framework.log.support.TestLogHelper;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.event.Level;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 日志系统引导生命周期集成测试
 * <p>
 * 验证引导生命周期管理的幂等性、配置重载与故障回滚能力。
 */
public class LogBootstrapTest {

    private TestLogHelper logHelper;
    private TestConfigContext firstContext;
    private TestConfigContext secondContext;

    @Before
    public void setup() {
        LogBootstrap.stop();
        LogEngine.getInstance().reset();
        logHelper = TestLogHelper.start();
    }

    @After
    public void teardown() {
        LogBootstrap.stop();
        LogEngine.getInstance().reset();
        logHelper.stop();
        if (firstContext != null) {
            firstContext.destroy();
        }
        if (secondContext != null) {
            secondContext.destroy();
        }
    }

    /**
     * 测试重复启动时系统的幂等性及状态一致性
     */
    @Test
    public void testStartIsIdempotentAndDoesNotImplicitlyReconfigure() {
        firstContext = TestConfigContext.create();
        secondContext = TestConfigContext.create();
        firstContext.put("team4u.log.dyeing", dyeingRule("rule-a", "First", "DEBUG"));
        secondContext.put("team4u.log.dyeing", dyeingRule("rule-b", "Second", "WARN"));

        LogBootstrap.start(LogBootstrap.Options.builder()
                .configManager(firstContext.getConfigManager())
                .build());
        LogBootstrap.start(LogBootstrap.Options.builder()
                .configManager(secondContext.getConfigManager())
                .build());

        Assert.assertEquals(LogBootstrap.State.STARTED, LogBootstrap.getState());
        Assert.assertTrue(LogBootstrap.isStarted());

        assertLevel("First", Level.DEBUG);
        assertLevel("Second", Level.INFO);
    }

    /**
     * 测试热重配操作能否正确切换底层的配置管理器
     */
    @Test
    public void testReconfigureSwitchesToNewConfigManager() {
        firstContext = TestConfigContext.create();
        secondContext = TestConfigContext.create();
        firstContext.put("team4u.log.dyeing", dyeingRule("rule-a", "First", "DEBUG"));
        secondContext.put("team4u.log.dyeing", dyeingRule("rule-b", "Second", "WARN"));

        LogBootstrap.start(LogBootstrap.Options.builder()
                .configManager(firstContext.getConfigManager())
                .build());
        LogBootstrap.reconfigure(LogBootstrap.Options.builder()
                .configManager(secondContext.getConfigManager())
                .build());

        Assert.assertEquals(LogBootstrap.State.STARTED, LogBootstrap.getState());
        assertLevel("First", Level.INFO);
        assertLevel("Second", Level.WARN);
    }

    /**
     * 测试热重配发生异常时，系统能否安全回滚至上一可用状态
     */
    @Test
    public void testReconfigureFailureRollsBackToPreviousBinding() {
        firstContext = TestConfigContext.create();
        secondContext = TestConfigContext.create();
        firstContext.put("team4u.log.dyeing", dyeingRule("rule-a", "First", "DEBUG"));

        LogBootstrap.start(LogBootstrap.Options.builder()
                .configManager(firstContext.getConfigManager())
                .build());

        ConfigManager failingManager = failingManager(secondContext.getConfigManager(), 3);
        try {
            LogBootstrap.reconfigure(LogBootstrap.Options.builder()
                    .configManager(failingManager)
                    .build());
            Assert.fail("Expected reconfigure to fail");
        } catch (RuntimeException expected) {
            Assert.assertEquals(LogBootstrap.State.STARTED, LogBootstrap.getState());
        }

        assertLevel("First", Level.DEBUG);
    }

    /**
     * 测试启动失败后，状态将变更为 FAILED，并允许执行恢复性启动
     */
    @Test
    public void testFailedStartMovesToFailedStateAndAllowsRecovery() {
        firstContext = TestConfigContext.create();
        secondContext = TestConfigContext.create();
        secondContext.put("team4u.log.dyeing", dyeingRule("rule-b", "Recovered", "WARN"));

        try {
            LogBootstrap.start(LogBootstrap.Options.builder()
                    .configManager(failingManager(firstContext.getConfigManager(), 1))
                    .build());
            Assert.fail("Expected start to fail");
        } catch (RuntimeException expected) {
            Assert.assertEquals(LogBootstrap.State.FAILED, LogBootstrap.getState());
            Assert.assertFalse(LogBootstrap.isStarted());
        }


        LogBootstrap.start(LogBootstrap.Options.builder()
                .configManager(secondContext.getConfigManager())
                .build());

        Assert.assertEquals(LogBootstrap.State.STARTED, LogBootstrap.getState());
        Assert.assertTrue(LogBootstrap.isStarted());
        assertLevel("Recovered", Level.WARN);
    }
    @Test
    public void testReconfigureFailureAndRollbackFailureMovesToFailedAndCleansComponents() {
        firstContext = TestConfigContext.create();
        secondContext = TestConfigContext.create();
        firstContext.put("team4u.log.dyeing", dyeingRule("rule-a", "First", "DEBUG"));

        ConfigManagerRollbackController rollbackManager =
                new ConfigManagerRollbackController(firstContext.getConfigManager());
        LogBootstrap.start(LogBootstrap.Options.builder()
                .configManager(rollbackManager)
                .build());
        assertLevel("First", Level.DEBUG);

        rollbackManager.failSnapshot = true;
        try {
            LogBootstrap.reconfigure(LogBootstrap.Options.builder()
                    .configManager(failingManager(secondContext.getConfigManager(), 1))
                    .build());
            Assert.fail("Expected reconfigure to fail");
        } catch (RuntimeException expected) {
            Assert.assertEquals(LogBootstrap.State.FAILED, LogBootstrap.getState());
            Assert.assertFalse(LogBootstrap.isStarted());
        }

        Assert.assertFalse(TargetedDyeingInterceptor.getInstance().hasActiveRules());
        Assert.assertNull(ProxyRuleRepository.getInstance().getRule(getClass().getName()));
        LogEvent oldDyeing = new LogEvent().setAction("First");
        Assert.assertTrue(RateLimitInterceptor.getInstance().handle(oldDyeing));
        assertLevel("First", Level.INFO);
    }

    private static final class ConfigManagerRollbackController implements ConfigManager {
        private final ConfigManager delegate;
        private volatile boolean failSnapshot;

        private ConfigManagerRollbackController(ConfigManager delegate) {
            this.delegate = delegate;
        }

        @Override
        public ConfigSnapshot currentSnapshot() {
            if (failSnapshot) {
                throw new IllegalStateException("boom-snapshot");
            }
            return delegate.currentSnapshot();
        }

        @Override
        public <T> T createProxy(String prefix, Class<T> configType) {
            return delegate.createProxy(prefix, configType);
        }

        @Override
        public AutoCloseable registerChangeListener(String keyPattern, ConfigChangeListener listener) {
            return delegate.registerChangeListener(keyPattern, listener);
        }
    }

    @Test
    public void testStopRespectsExternalEngineOwnershipAndKeepsHelperUsable() {
        firstContext = TestConfigContext.create();
        LogEngine coreEngine = LogEngine.getInstance();

        try {
            LogBootstrap.start(LogBootstrap.Options.builder()
                    .configManager(firstContext.getConfigManager())
                    .build());
            LogEngine governanceEngine = LogEngine.getInstance();
            Assert.assertNotSame(coreEngine, governanceEngine);

            LogEngine externalEngine = LogEngine.builder().build();
            LogEngine.install(externalEngine);

            LogBootstrap.stop();

            Assert.assertSame(externalEngine, LogEngine.getInstance());
            Assert.assertEquals(LogBootstrap.State.STOPPED, LogBootstrap.getState());
            Assert.assertFalse(LogBootstrap.isStarted());
            Assert.assertFalse(TargetedDyeingInterceptor.getInstance().hasActiveRules());
            Assert.assertNull(ProxyRuleRepository.getInstance().getRule(getClass().getName()));
            assertLevel("Ownership", Level.INFO);
        } finally {
            LogEngine current = LogEngine.getInstance();
            if (current != coreEngine) {
                Assert.assertTrue(LogEngine.restore(current, coreEngine));
            }
        }
    }

    /**
     * 测试停止操作的幂等性以及资源回收后的重启能力
     */
    @Test
    public void testStopIsIdempotentAndSupportsRestart() {
        firstContext = TestConfigContext.create();
        secondContext = TestConfigContext.create();
        firstContext.put("team4u.log.dyeing", dyeingRule("rule-a", "First", "DEBUG"));
        secondContext.put("team4u.log.dyeing", dyeingRule("rule-b", "Second", "WARN"));

        LogBootstrap.start(LogBootstrap.Options.builder()
                .configManager(firstContext.getConfigManager())
                .build());
        LogBootstrap.stop();
        LogBootstrap.stop();

        Assert.assertEquals(LogBootstrap.State.STOPPED, LogBootstrap.getState());
        Assert.assertFalse(LogBootstrap.isStarted());
        assertLevel("First", Level.INFO);

        LogBootstrap.start(LogBootstrap.Options.builder()
                .configManager(secondContext.getConfigManager())
                .build());
        assertLevel("Second", Level.WARN);
    }

    private void assertLevel(String action, Level expectedLevel) {
        logHelper.clear();
        Loggers.of(getClass()).action(action).success().log();
        LogEvent event = logHelper.lastEvent();
        Assert.assertNotNull(event);
        Assert.assertEquals(expectedLevel, event.getLevel());
    }

    private String dyeingRule(String id, String action, String level) {
        return "[{\"id\":\"" + id + "\",\"condition\":\"meta_action == '" + action + "'\",\"targetLevel\":\""
                + level + "\"}]";
    }

    private ConfigManager failingManager(ConfigManager delegate, int failOnRegisterCount) {
        AtomicInteger registerCalls = new AtomicInteger();
        return new ConfigManager() {
            @Override
            public ConfigSnapshot currentSnapshot() {
                return delegate.currentSnapshot();
            }

            @Override
            public <T> T createProxy(String prefix, Class<T> configType) {
                return delegate.createProxy(prefix, configType);
            }

            @Override
            public AutoCloseable registerChangeListener(String keyPattern, ConfigChangeListener listener) {
                if (registerCalls.incrementAndGet() >= failOnRegisterCount) {
                    throw new IllegalStateException("boom-register-" + failOnRegisterCount);
                }
                return delegate.registerChangeListener(keyPattern, listener);
            }
        };
    }
}
