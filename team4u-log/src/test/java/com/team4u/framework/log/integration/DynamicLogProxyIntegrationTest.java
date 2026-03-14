package com.team4u.framework.log.integration;

import com.team4u.framework.config.test.TestConfigContext;
import com.team4u.framework.log.LogBootstrap;
import com.team4u.framework.log.core.LogEvent;
import com.team4u.framework.log.pipeline.interceptor.TargetedDyeingInterceptor;
import com.team4u.framework.log.proxy.LogProxyFactory;
import com.team4u.framework.log.proxy.ProxyRuleRepository;
import com.team4u.framework.log.support.TestLogHelper;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.event.Level;

/**
 * 动态配置驱动的日志代理集成测试
 * <p>
 * 验证：在不修改源码（无注解）的情况下，通过配置中心动态控制拦截行为、脱敏及异常降级。
 */
public class DynamicLogProxyIntegrationTest {

    private TestLogHelper logHelper;
    private TestConfigContext configContext;

    @Before
    public void setup() {
        logHelper = TestLogHelper.start();
        configContext = TestConfigContext.create();
        // 初始化日志系统，对接测试配置上下文
        LogBootstrap.global().configManager(configContext.getManager()).start();
    }

    @After
    public void teardown() {
        logHelper.stop();
        configContext.destroy();
        ProxyRuleRepository.getInstance().reset();
        TargetedDyeingInterceptor.getInstance().reset();
    }

    @Test
    public void testDynamicIgnoreExceptions() throws InterruptedException {
        // 1. 准备：为第三方类配置动态拦截规则，并将 RuntimeException 设为忽略异常（业务降级）
        String className = ThirdPartyService.class.getName();
        String proxyConfig = "{" +
                "  \"" + className + "\": {" +
                "    \"methods\": [\"send\"]," +
                "    \"ignoreExceptions\": [\"java.lang.RuntimeException\"]" +
                "  }" +
                "}";

        configContext.put("team4u.log.proxy", proxyConfig);

        String maskConfig = "{" +
                "  \"*\": {" +
                "    \"mobile\": \"MOBILE\"" +
                "  }" +
                "}";

        configContext.put("team4u.mask.rules", maskConfig);
        Thread.sleep(50);

        // 2. 创建动态代理（该类没有任何 @AutoLogTrace 注解）
        ThirdPartyService service = LogProxyFactory.createDynamicProxy(new ThirdPartyService());

        // 3. 执行：触发异常
        try {
            service.send("13812345678", "Hello");
        } catch (Exception e) {
            // 4. 断言：验证日志级别是否降级为 WARN (由 ignoreExceptions 驱动)
            LogEvent event = logHelper.lastEvent();
            Assert.assertNotNull(event);
            Assert.assertEquals(Level.WARN, event.getLevel());
            Assert.assertEquals("business_error", event.getStatus());

            // 同时验证动态脱敏是否生效
            String json = logHelper.lastJson();
            Assert.assertTrue("手机号应被脱敏", json.contains("138*****678"));
        }
    }

    @Test
    public void testDynamicSlowLog() throws InterruptedException {
        String className = ThirdPartyService.class.getName();
        String proxyConfig = "{" +
                "  \"" + className + "\": {" +
                "    \"methods\": [\"*\"]," +
                "    \"slowThreshold\": 50" +
                "  }" +
                "}";

        configContext.put("team4u.log.proxy", proxyConfig);
        Thread.sleep(50);

        ThirdPartyService service = LogProxyFactory.createDynamicProxy(new ThirdPartyService());
        service.slowMethod();

        LogEvent event = logHelper.lastEvent();
        Assert.assertNotNull(event);
        Assert.assertEquals(Level.WARN, event.getLevel());
        Assert.assertEquals("slow_success", event.getStatus());
    }

    @Test
    public void testProxyRuleInvalidConfigKeepOldRules() throws InterruptedException {
        String className = ThirdPartyService.class.getName();
        String validConfig = "{" +
                "  \"" + className + "\": {" +
                "    \"methods\": [\"send\"]," +
                "    \"ignoreExceptions\": [\"java.lang.RuntimeException\"]" +
                "  }" +
                "}";

        configContext.put("team4u.log.proxy", validConfig);
        Thread.sleep(50);

        ThirdPartyService service = LogProxyFactory.createDynamicProxy(new ThirdPartyService());

        try {
            service.send("13812345678", "before-bad-config");
        } catch (Exception ignored) {
            LogEvent before = logHelper.lastEvent();
            Assert.assertNotNull(before);
            Assert.assertEquals(Level.WARN, before.getLevel());
            Assert.assertEquals("business_error", before.getStatus());
        }

        logHelper.clear();

        // 推送非法 JSON，验证热更新失败时保留上一版规则
        configContext.put("team4u.log.proxy", "{");
        Thread.sleep(50);

        try {
            service.send("13812345678", "after-bad-config");
        } catch (Exception ignored) {
            LogEvent after = logHelper.lastEvent();
            Assert.assertNotNull(after);
            Assert.assertEquals(Level.WARN, after.getLevel());
            Assert.assertEquals("business_error", after.getStatus());
        }
    }

    @Test
    public void testProxyRuleInvalidConfigAtStartupDoesNotThrow() throws InterruptedException {
        configContext.put("team4u.log.proxy", "{");
        Thread.sleep(50);

        ThirdPartyService service = LogProxyFactory.createDynamicProxy(new ThirdPartyService());

        try {
            service.send("13812345678", "startup-bad-config");
            Assert.fail("预期抛出业务异常");
        } catch (Exception ignored) {
            Assert.assertNull("启动时坏配置不应激活动态代理规则", logHelper.lastEvent());
        }
    }

    /**
     * 模拟第三方类库（无任何日志注解）
     */
    public static class ThirdPartyService {
        public void send(String mobile, String content) {
            throw new RuntimeException("第三方调用失败");
        }

        public void slowMethod() throws InterruptedException {
            Thread.sleep(60);
        }
    }
}
