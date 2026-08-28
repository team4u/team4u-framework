package com.team4u.framework.log;

import com.team4u.framework.config.test.TestConfigContext;
import com.team4u.framework.log.core.LogEngine;
import com.team4u.framework.log.core.LogEvent;
import com.team4u.framework.log.pipeline.interceptor.RateLimitInterceptor;
import com.team4u.framework.log.proxy.LogProxyFactory;
import com.team4u.framework.log.support.TestLogHelper;
import com.team4u.framework.serializer.json.JsonUtil;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.event.Level;

import java.util.LinkedHashMap;
import java.util.Map;

public class LogGovernanceQuickstartTest {

    private TestConfigContext configContext;
    private TestLogHelper helper;

    @Before
    public void setUp() {
        LogBootstrap.stop();
        LogEngine.getInstance().reset();
        helper = TestLogHelper.start();
        configContext = TestConfigContext.create();
        configContext.put("team4u.mask.rules", "{\"java.util.LinkedHashMap\":{\"mobile\":\"MOBILE\"}}");
        configContext.put("team4u.log.dyeing",
                "[{\"id\":\"quickstart\",\"condition\":\"meta_action == 'Pay'\",\"targetLevel\":\"DEBUG\"}]");
        configContext.put("team4u.log.finops",
                "{\"maxLogLength\":500,\"maxStringLength\":2000,\"errorLimitPerSecond\":1}");
        configContext.put("team4u.log.proxy", proxyConfig());
    }

    @After
    public void tearDown() {
        LogBootstrap.stop();
        helper.stop();
        configContext.destroy();
        LogEngine.getInstance().reset();
    }

    @Test
    public void governanceQuickstartAppliesProviderAndNonDefaultRuntimeRules() {
        Map<String, String> providerCheck = new LinkedHashMap<>();
        providerCheck.put("provider", "jackson");
        Assert.assertEquals(providerCheck, JsonUtil.toBean(JsonUtil.toJsonStr(providerCheck), Map.class));

        LogBootstrap.start(LogBootstrap.Options.builder()
                .configManager(configContext.getConfigManager())
                .build());
        LogEngine installedEngine = LogEngine.getInstance();

        LogBootstrap.start(LogBootstrap.Options.builder().build());
        LogBootstrap.reconfigure(LogBootstrap.Options.builder()
                .configManager(configContext.getConfigManager())
                .build());
        Assert.assertSame(installedEngine, LogEngine.getInstance());

        Loggers.of(getClass())
                .action("Pay")
                .put("mobile", "13812345678")
                .put("padding", "1234567890123456789012345678901234567890")
                .success()
                .log();

        LogEvent event = helper.lastEvent();
        Assert.assertEquals(Level.DEBUG, event.getLevel());
        Assert.assertTrue(helper.lastJson().startsWith("{"));
        Assert.assertTrue("Mask rule did not apply: " + helper.lastJson(), helper.lastJson().contains("138*****678"));
        Assert.assertTrue("Provider output was not JSON: " + helper.lastJson(), helper.lastJson().startsWith("{"));

        PaymentService service = LogProxyFactory.createDynamicProxy(new PaymentService());
        try {
            service.pay("13812345678");
            Assert.fail("Expected business exception");
        } catch (RuntimeException expected) {
            LogEvent proxyEvent = helper.lastEvent();
            Assert.assertEquals(Level.WARN, proxyEvent.getLevel());
            Assert.assertEquals("business_error", proxyEvent.getStatus());
            Assert.assertTrue(helper.lastJson().contains("138*****678"));
        }
    }

    @Test
    public void finOpsErrorLimitHotUpdateChangesNewSignaturesImmediately() {
        LogBootstrap.start(LogBootstrap.Options.builder()
                .configManager(configContext.getConfigManager())
                .build());

        LogEvent first = errorEvent("finops-first");
        Assert.assertTrue(RateLimitInterceptor.getInstance().handle(first));
        LogEvent second = errorEvent("finops-first");
        Assert.assertFalse(RateLimitInterceptor.getInstance().handle(second));
        Assert.assertTrue(second.isSuppressed());

        configContext.put("team4u.log.finops",
                "{\"maxLogLength\":500,\"maxStringLength\":2000,\"errorLimitPerSecond\":2}");
        RateLimitInterceptor.getInstance().stop();

        for (int i = 0; i < 3; i++) {
            LogEvent event = errorEvent("finops-hot-" + i);
            Assert.assertTrue(RateLimitInterceptor.getInstance().handle(event));
        }
    }

    private LogEvent errorEvent(String action) {
        return new LogEvent().setLoggerName(getClass().getName()).setAction(action)
                .setException(new RuntimeException("finops"));
    }
    private String proxyConfig() {
        return "{\"" + PaymentService.class.getName() + "\":{"
                + "\"methods\":[\"pay\"],"
                + "\"ignoreExceptions\":[\"java.lang.RuntimeException\"]}}";
    }

    public static class PaymentService {
        public void pay(String mobile) {
            throw new RuntimeException("payment failed");
        }
    }
}
