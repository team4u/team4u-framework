package com.team4u.framework.ratelimiter.api;

import com.team4u.framework.config.test.TestConfigContext;
import com.team4u.framework.kv.memory.InMemoryKvStore;
import com.team4u.framework.kv.test.TestKvContext;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 限流静态门面单元测试：init/destroy 生命周期、拒绝异常、懒加载默认引擎
 *
 * @author jay.wu
 */
public class RateLimitersTest {

    @After
    public void tearDown() {
        RateLimiters.destroy();
    }

    private TestConfigContext configWithThresholdOne() {
        TestConfigContext config = TestConfigContext.create();
        config.put("team4u.ratelimiter.api.point",
                "[{\"id\":\"fw\",\"algorithm\":\"fixed-window\","
                        + "\"windowMillis\":60000,\"threshold\":1}]");
        return config;
    }

    @Test
    public void initThenAcquireAndDestroy() {
        TestConfigContext config = configWithThresholdOne();
        TestKvContext kv = TestKvContext.create();
        try {
            RateLimiters.init(config.getConfigManager(), kv.store(), kv.clock());

            assertTrue(RateLimiters.tryAcquire("api.point", null));
            assertFalse("阈值 1：第二次拒绝", RateLimiters.tryAcquire("api.point", null));

            RateLimitResult allowed = RateLimiters.acquire("no.rule.point", null);
            assertTrue("无规则检查点直接放行", allowed.isAllowed());
            assertEquals(RateLimitReason.NO_RULE, allowed.getReason());
        } finally {
            RateLimiters.destroy();
            config.destroy();
            kv.close();
        }
    }

    @Test
    public void acquireReturnsDeniedResultWithoutThrowing() {
        TestConfigContext config = configWithThresholdOne();
        TestKvContext kv = TestKvContext.create();
        try {
            RateLimiters.init(config.getConfigManager(), kv.store(), kv.clock());
            assertTrue(RateLimiters.acquire("api.point", null).isAllowed());

            // 拒绝不是异常：返回完整裁决结果（规则/原因可提取），何时抛由调用方决定
            RateLimitResult denied = RateLimiters.acquire("api.point", null);
            assertFalse("阈值 1：第二次拒绝", denied.isAllowed());
            assertEquals("api.point", denied.getPoint());
            assertEquals("fw", denied.getRuleId());
            assertEquals(RateLimitReason.THRESHOLD, denied.getReason());
        } finally {
            RateLimiters.destroy();
            config.destroy();
            kv.close();
        }
    }

    @Test
    public void destroyResetsEngineForIsolation() {
        TestConfigContext first = configWithThresholdOne();
        TestKvContext kv = TestKvContext.create();
        try {
            RateLimiters.init(first.getConfigManager(), kv.store(), kv.clock());
            assertTrue(RateLimiters.tryAcquire("api.point", null));
            assertFalse(RateLimiters.tryAcquire("api.point", null));
            RateLimiters.destroy();

            // destroy 复位后：懒加载默认引擎（全局配置 + 内存存储），原规则不再生效
            assertTrue("复位后懒加载默认引擎按无规则放行",
                    RateLimiters.tryAcquire("api.point", null));
        } finally {
            RateLimiters.destroy();
            first.destroy();
            kv.close();
        }
    }

    @Test
    public void lazyDefaultEngineCreatedWithoutInit() {
        // 未 init：首次调用懒加载默认引擎（ConfigManager.global() + 内存存储）
        RateLimitResult result = RateLimiters.acquire("lazy.point", null);
        assertTrue(result.isAllowed());
        assertEquals(RateLimitReason.NO_RULE, result.getReason());
        assertNotNull(result.getPoint());
        // 基本类型便捷入口
        assertTrue(RateLimiters.tryAcquire("lazy.point", null));
    }

    @Test
    public void reinitReplacesEngine() {
        TestConfigContext config = configWithThresholdOne();
        TestKvContext kv = TestKvContext.create();
        try {
            RateLimiters.init(config.getConfigManager(), new InMemoryKvStore(), kv.clock());
            RateLimiters.init(config.getConfigManager(), kv.store(), kv.clock());

            assertTrue(RateLimiters.tryAcquire("api.point", null));
            // 新引擎计数在 kv.store 上（首个 InMemoryKvStore 未被使用）
            assertFalse(RateLimiters.tryAcquire("api.point", null));
        } finally {
            RateLimiters.destroy();
            config.destroy();
            kv.close();
        }
    }
}
