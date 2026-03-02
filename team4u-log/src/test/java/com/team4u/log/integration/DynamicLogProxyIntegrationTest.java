package com.team4u.log.integration;

import com.team4u.framework.config.test.TestConfigContext;
import com.team4u.log.LogBootstrap;
import com.team4u.log.core.LogEvent;
import com.team4u.log.pipeline.interceptor.TargetedDyeingInterceptor;
import com.team4u.log.proxy.LogProxyFactory;
import com.team4u.log.support.TestLogHelper;
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
        LogBootstrap.start(configContext.getManager());
    }

    @After
    public void teardown() {
        logHelper.stop();
        configContext.destroy();
        TargetedDyeingInterceptor.getInstance().reset();
    }

    @Test
    public void testDynamicIgnoreExceptions() {
        // 1. 准备：为第三方类配置动态拦截规则，并将 RuntimeException 设为忽略异常（业务降级）
        String className = ThirdPartyService.class.getName();
        String configJson = "{" +
                "  \"proxyRules\": {" +
                "    \"" + className + "\": {" +
                "      \"methods\": [\"send\"]," +
                "      \"ignoreExceptions\": [\"java.lang.RuntimeException\"]" +
                "    }" +
                "  }," +
                "  \"maskRules\": {" +
                "    \"*\": {" +
                "      \"mobile\": \"MOBILE\"" +
                "    }" +
                "  }" +
                "}";

        configContext.put("team4u.log.config", configJson);

        // 2. 创建动态代理（该类没有任何 @AutoLogTrace 注解）
        ThirdPartyService service = LogProxyFactory.createDynamicProxy(new ThirdPartyService());

        // 3. 执行：触发异常
        try {
            service.send("13812345678", "Hello");
        } catch (RuntimeException e) {
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
        String configJson = "{" +
                "  \"proxyRules\": {" +
                "    \"" + className + "\": {" +
                "      \"methods\": [\"*\"]," +
                "      \"slowThreshold\": 50" +
                "    }" +
                "  }" +
                "}";

        configContext.put("team4u.log.config", configJson);

        ThirdPartyService service = LogProxyFactory.createDynamicProxy(new ThirdPartyService());
        service.slowMethod();

        LogEvent event = logHelper.lastEvent();
        Assert.assertEquals(Level.WARN, event.getLevel());
        Assert.assertEquals("slow_success", event.getStatus());
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
