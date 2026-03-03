package com.team4u.log.core;

import com.team4u.log.config.LogConfigManager;
import com.team4u.log.config.LogDynamicConfig;
import com.team4u.log.config.LogDynamicConfig.DyeingRule;
import com.team4u.log.pipeline.LogInterceptor;
import com.team4u.log.pipeline.LogInterceptorManager;
import com.team4u.log.pipeline.interceptor.MdcEnrichInterceptor;
import com.team4u.log.pipeline.interceptor.TargetedDyeingInterceptor;
import org.junit.Assert;
import org.junit.Test;

import java.util.Collections;

/**
 * 日志拦截器管理器单元测试
 */
public class LogInterceptorManagerTest {

    @Test
    public void testReset() {
        LogInterceptorManager manager = new LogInterceptorManager();

        // 1. 初始化监听器（模拟 Bootstrap 行为）
        LogConfigManager.getInstance().addListener(TargetedDyeingInterceptor.getInstance());

        // 2. 修改拦截器状态
        MdcEnrichInterceptor.getInstance().setTraceIdKey("customTraceId");

        LogDynamicConfig config = new LogDynamicConfig();
        LogDynamicConfig.FinOpsConfig finOpsConfig = new LogDynamicConfig.FinOpsConfig();
        finOpsConfig.setErrorLimitPerSecond(50);
        config.setFinOpsConfig(finOpsConfig);

        DyeingRule activeRule = new DyeingRule();
        activeRule.setId("test");
        activeRule.setCondition("true");
        config.setDyeingRules(Collections.singletonList(activeRule));

        LogConfigManager.getInstance().setCurrentConfig(config);

        // 3. 验证状态已修改
        Assert.assertTrue(TargetedDyeingInterceptor.getInstance().hasActiveRules());

        // 4. 执行重置
        manager.reset();
        // 同时也重置配置
        LogConfigManager.getInstance().setCurrentConfig(new LogDynamicConfig());

        // 5. 验证状态已恢复默认
        Assert.assertFalse(TargetedDyeingInterceptor.getInstance().hasActiveRules());
    }

    @Test
    public void testCustomInterceptorReset() {
        LogInterceptorManager manager = new LogInterceptorManager();

        MockInterceptor mock = new MockInterceptor();
        manager.register(mock);

        mock.setState(1);
        Assert.assertEquals(1, mock.getState());

        manager.reset();
        Assert.assertEquals(0, mock.getState());
    }

    private static class MockInterceptor implements LogInterceptor {
        private int state = 0;

        public int getState() {
            return state;
        }

        public void setState(int state) {
            this.state = state;
        }

        @Override
        public void reset() {
            this.state = 0;
        }

        @Override
        public boolean handle(LogEvent event) {
            return true;
        }
    }
}
