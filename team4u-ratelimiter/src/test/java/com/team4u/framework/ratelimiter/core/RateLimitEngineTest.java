package com.team4u.framework.ratelimiter.core;

import com.team4u.framework.config.test.TestConfigContext;
import com.team4u.framework.kv.CounterCapable;
import com.team4u.framework.kv.KvRecord;
import com.team4u.framework.kv.KvStore;
import com.team4u.framework.kv.KvStoreException;
import com.team4u.framework.kv.KvStores;
import com.team4u.framework.kv.PutMode;
import com.team4u.framework.kv.SpaceKey;
import com.team4u.framework.kv.memory.InMemoryKvStore;
import com.team4u.framework.kv.test.TestKvContext;
import com.team4u.framework.ratelimiter.api.RateLimitConfigException;
import com.team4u.framework.ratelimiter.api.RateLimitReason;
import com.team4u.framework.ratelimiter.api.RateLimitResult;
import com.team4u.framework.ratelimiter.store.RateLimitStores;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 限流引擎单元测试：规则校验、优先级与首拒即停、键模板渲染、存储路由、
 * failOpen/failClosed、热更新与热更失败保旧
 *
 * @author jay.wu
 */
public class RateLimitEngineTest {

    private TestConfigContext config;
    private TestKvContext kv;
    private InMemoryKvStore store;
    private RateLimitEngine engine;

    @Before
    public void setUp() {
        config = TestConfigContext.create();
        kv = TestKvContext.create();
        store = kv.store();
        engine = new RateLimitEngine(config.getConfigManager(), store, kv.clock());
    }

    @After
    public void tearDown() {
        engine.destroy();
        config.destroy();
        kv.close();
    }

    private void rules(String point, String json) {
        config.put(RateLimitEngine.DEFAULT_CONFIG_PATTERN.replace("*", point), json);
    }

    // ------------------------------------------------- 无规则

    @Test
    public void noRuleAllows() {
        RateLimitResult result = engine.acquire("nothing", null);
        assertTrue(result.isAllowed());
        assertEquals(RateLimitReason.NO_RULE, result.getReason());
        assertNull(result.getRuleId());
        assertNull(result.getRemaining());
        assertEquals("nothing", result.getPoint());
        assertEquals(kv.clock().millis(), result.getDecisionTimeMillis());
    }

    @Test
    public void emptyPointRejected() {
        try {
            engine.acquire("", null);
            fail("expected RateLimitConfigException");
        } catch (RateLimitConfigException ignored) {
        }
    }

    // ------------------------------------------------- 优先级与首拒即停

    @Test
    public void rulesSortedByPriorityFirstDenyStops() {
        rules("multi", "["
                + "{\"id\":\"low\",\"algorithm\":\"fixed-window\",\"windowMillis\":10000,\"threshold\":1,\"priority\":0},"
                + "{\"id\":\"high\",\"algorithm\":\"fixed-window\",\"windowMillis\":10000,\"threshold\":1,\"priority\":10}"
                + "]");

        // 全部通过：返回最后一条（priority 最低的 low）通过结果
        RateLimitResult passed = engine.acquire("multi", null);
        assertTrue(passed.isAllowed());
        assertEquals(RateLimitReason.PASS, passed.getReason());
        assertEquals("low", passed.getRuleId());
        assertEquals("multi", passed.getPoint());

        // 第二次：high（priority 10）先执行并拒绝，首拒即停
        RateLimitResult denied = engine.acquire("multi", null);
        assertFalse(denied.isAllowed());
        assertEquals("high", denied.getRuleId());
        assertEquals(RateLimitReason.THRESHOLD, denied.getReason());

        // 首拒即停：low 规则未被再次执行（计数器保持 1）
        CounterCapable lowCounter = KvStores.capabilityOf(store, CounterCapable.class);
        assertEquals(1, lowCounter.incrementAndGet(
                SpaceKey.of(RateLimitEngine.DEFAULT_SPACE, "low.multi"), 0, 0));
    }

    // ------------------------------------------------- 键模板渲染

    @Test
    public void keyTemplateRenderedFromMapContext() {
        rules("tpl", "[{\"id\":\"byUser\",\"algorithm\":\"fixed-window\","
                + "\"windowMillis\":60000,\"threshold\":1,\"key\":\"${userId}\"}]");

        Map<String, Object> u1 = Collections.singletonMap("userId", "u1");
        Map<String, Object> u2 = Collections.singletonMap("userId", "u2");

        assertTrue(engine.acquire("tpl", u1).isAllowed());
        assertTrue("不同变量值独立计数", engine.acquire("tpl", u2).isAllowed());
        assertFalse(engine.acquire("tpl", u1).isAllowed());
    }

    @Test
    public void keyTemplateRenderedFromBeanContext() {
        rules("bean", "[{\"id\":\"byUser\",\"algorithm\":\"fixed-window\","
                + "\"windowMillis\":60000,\"threshold\":1,\"key\":\"user-${userId}\"}]");

        assertTrue(engine.acquire("bean", new Ctx("u1")).isAllowed());
        assertTrue(engine.acquire("bean", new Ctx("u2")).isAllowed());
        assertFalse(engine.acquire("bean", new Ctx("u1")).isAllowed());
    }

    @Test
    public void missingVariableRendersEmptyString() {
        rules("miss", "[{\"id\":\"byUser\",\"algorithm\":\"fixed-window\","
                + "\"windowMillis\":60000,\"threshold\":1,\"key\":\"${userId}\"}]");

        // 缺变量渲染为空串：空串键独立计数一次
        assertTrue(engine.acquire("miss", new HashMap<String, Object>()).isAllowed());
        assertFalse(engine.acquire("miss", new HashMap<String, Object>()).isAllowed());

        // 与空串变量值的键相同
        assertFalse(engine.acquire("miss", Collections.singletonMap("userId", "")).isAllowed());
    }

    @Test
    public void colonInRenderedKeySanitized() {
        rules("colon", "[{\"id\":\"byUser\",\"algorithm\":\"fixed-window\","
                + "\"windowMillis\":60000,\"threshold\":1,\"key\":\"${userId}\"}]");

        Map<String, Object> composite = Collections.singletonMap("userId", "tenant:user");
        assertTrue("含 ':' 的上下文值应被清洗组键而非报错",
                engine.acquire("colon", composite).isAllowed());
        assertFalse(engine.acquire("colon", composite).isAllowed());
    }

    // ------------------------------------------------- 存储路由与能力校验

    @Test
    public void namedStoreRoutesCounting() {
        InMemoryKvStore named = new InMemoryKvStore();
        RateLimitStores.global().register("dedicated", named);
        try {
            rules("named", "[{\"id\":\"fw\",\"algorithm\":\"fixed-window\","
                    + "\"windowMillis\":10000,\"threshold\":1,\"store\":\"dedicated\"}]");

            assertTrue(engine.acquire("named", null).isAllowed());
            assertFalse("计数发生在命名存储上", engine.acquire("named", null).isAllowed());

            // 命名存储同键计数为 2（被拒请求同样计数），默认存储同键保持 0
            CounterCapable namedCounter = KvStores.capabilityOf(named, CounterCapable.class);
            assertEquals(2, namedCounter.incrementAndGet(
                    SpaceKey.of(RateLimitEngine.DEFAULT_SPACE, "fw.named"), 0, 0));
            CounterCapable defaultCounter = KvStores.capabilityOf(store, CounterCapable.class);
            assertEquals(0, defaultCounter.incrementAndGet(
                    SpaceKey.of(RateLimitEngine.DEFAULT_SPACE, "fw.named"), 0, 0));
        } finally {
            named.close();
        }
    }

    @Test
    public void unregisteredStoreRejectedOnFirstLoad() {
        rules("bad", "[{\"id\":\"fw\",\"algorithm\":\"fixed-window\","
                + "\"windowMillis\":1000,\"threshold\":1,\"store\":\"missing\"}]");
        try {
            engine.acquire("bad", null);
            fail("expected RateLimitConfigException");
        } catch (RateLimitConfigException e) {
            assertTrue(e.getMessage().contains("missing"));
        }
    }

    @Test
    public void storeWithoutCapabilityRejected() {
        engine = new RateLimitEngine(config.getConfigManager(), new PlainValueStore(), kv.clock());
        rules("plain", "[{\"id\":\"fw\",\"algorithm\":\"fixed-window\","
                + "\"windowMillis\":1000,\"threshold\":1}]");
        try {
            engine.acquire("plain", null);
            fail("expected RateLimitConfigException");
        } catch (RateLimitConfigException e) {
            assertTrue(e.getMessage().contains("CounterCapable"));
        }
    }

    @Test
    public void hotUpdateFailureKeepsOldRules() {
        rules("keep", "[{\"id\":\"fw\",\"algorithm\":\"fixed-window\","
                + "\"windowMillis\":10000,\"threshold\":2}]");
        assertTrue(engine.acquire("keep", null).isAllowed());
        assertTrue(engine.acquire("keep", null).isAllowed());

        // 热更失败（存储未注册）：注册表保留旧规则，限流行为不变
        rules("keep", "[{\"id\":\"fw\",\"algorithm\":\"fixed-window\","
                + "\"windowMillis\":10000,\"threshold\":1,\"store\":\"missing\"}]");
        RateLimitResult result = engine.acquire("keep", null);
        assertFalse("旧规则（阈值 2）应继续生效", result.isAllowed());
        assertEquals("fw", result.getRuleId());
    }

    // ------------------------------------------------- 规则校验

    @Test
    public void invalidRuleJsonRejected() {
        rules("bad", "{not-json");
        assertConfigError("bad");
    }

    @Test
    public void emptyRuleListRejected() {
        rules("bad", "[]");
        assertConfigError("bad");
    }

    @Test
    public void blankRuleIdRejected() {
        rules("bad", "[{\"id\":\"\",\"algorithm\":\"fixed-window\",\"windowMillis\":1,\"threshold\":1}]");
        assertConfigError("bad");
    }

    @Test
    public void duplicatedRuleIdRejected() {
        rules("bad", "["
                + "{\"id\":\"a\",\"algorithm\":\"fixed-window\",\"windowMillis\":1,\"threshold\":1},"
                + "{\"id\":\"a\",\"algorithm\":\"fixed-window\",\"windowMillis\":1,\"threshold\":2}"
                + "]");
        assertConfigError("bad");
    }

    @Test
    public void unknownAlgorithmRejected() {
        rules("bad", "[{\"id\":\"a\",\"algorithm\":\"no-such\",\"windowMillis\":1,\"threshold\":1}]");
        assertConfigError("bad");
    }

    @Test
    public void nonPositiveWindowOrThresholdRejected() {
        rules("w", "[{\"id\":\"a\",\"algorithm\":\"fixed-window\",\"windowMillis\":0,\"threshold\":1}]");
        assertConfigError("w");
        rules("t", "[{\"id\":\"a\",\"algorithm\":\"fixed-window\",\"windowMillis\":1,\"threshold\":0}]");
        assertConfigError("t");
    }

    @Test
    public void historyWindowWithoutHistoryPathRejected() {
        rules("bad", "[{\"id\":\"a\",\"algorithm\":\"history-window\",\"windowMillis\":1,\"threshold\":1}]");
        assertConfigError("bad");
    }

    @Test
    public void colonInRuleIdOrKeyRejected() {
        rules("i", "[{\"id\":\"a:b\",\"algorithm\":\"fixed-window\",\"windowMillis\":1,\"threshold\":1}]");
        assertConfigError("i");
        rules("k", "[{\"id\":\"a\",\"algorithm\":\"fixed-window\",\"windowMillis\":1,\"threshold\":1,"
                + "\"key\":\"${a:b}\"}]");
        assertConfigError("k");
    }

    @Test
    public void historyWindowRunsStatelessWithoutStore() {
        rules("hist", "[{\"id\":\"hw\",\"algorithm\":\"history-window\","
                + "\"windowMillis\":1000,\"threshold\":1,\"historyPath\":\"client.history\"}]");
        Map<String, Object> context = Collections.singletonMap("client",
                Collections.singletonMap("history", Collections.singletonList(kv.clock().millis())));

        assertFalse("已有 1 条历史且阈值 1：本请求拒绝",
                engine.acquire("hist", context).isAllowed());
        Map<String, Object> empty = Collections.singletonMap("client",
                Collections.singletonMap("history", Collections.emptyList()));
        assertTrue(engine.acquire("hist", empty).isAllowed());
    }

    // ------------------------------------------------- 存储故障处置

    @Test
    public void failOpenPassesOnStoreError() {
        InMemoryKvStore broken = mock(InMemoryKvStore.class);
        when(broken.incrementAndGet(any(SpaceKey.class), anyLong(), anyLong()))
                .thenThrow(new KvStoreException("store down"));
        rules("open", "[{\"id\":\"fw\",\"algorithm\":\"fixed-window\","
                + "\"windowMillis\":1000,\"threshold\":1,\"failOpen\":true}]");
        // 覆盖默认存储：同名重注册 defaultStore 指向的注册名不可行，直接建引擎
        RateLimitEngine failOpenEngine = new RateLimitEngine(config.getConfigManager(), broken, kv.clock());
        try {
            RateLimitResult result = failOpenEngine.acquire("open", null);
            assertTrue("failOpen=true 时存储故障应放行", result.isAllowed());
            assertEquals(RateLimitReason.PASS, result.getReason());
            assertEquals("fw", result.getRuleId());
        } finally {
            failOpenEngine.destroy();
        }
    }

    @Test
    public void failClosedDeniesWithStoreError() {
        InMemoryKvStore broken = mock(InMemoryKvStore.class);
        when(broken.incrementAndGet(any(SpaceKey.class), anyLong(), anyLong()))
                .thenThrow(new KvStoreException("store down"));
        rules("closed", "[{\"id\":\"fw\",\"algorithm\":\"fixed-window\","
                + "\"windowMillis\":1000,\"threshold\":1,\"failOpen\":false}]");
        RateLimitEngine failClosedEngine = new RateLimitEngine(config.getConfigManager(), broken, kv.clock());
        try {
            RateLimitResult result = failClosedEngine.acquire("closed", null);
            assertFalse(result.isAllowed());
            assertEquals(RateLimitReason.STORE_ERROR, result.getReason());
            assertEquals("fw", result.getRuleId());
            assertEquals("closed", result.getPoint());
        } finally {
            failClosedEngine.destroy();
        }
    }

    // ------------------------------------------------- 热更新生效

    @Test
    public void hotReloadedThresholdTakesEffect() {
        rules("hot", "[{\"id\":\"fw\",\"algorithm\":\"fixed-window\","
                + "\"windowMillis\":10000,\"threshold\":2}]");
        assertTrue(engine.acquire("hot", null).isAllowed());
        assertTrue(engine.acquire("hot", null).isAllowed());
        assertFalse(engine.acquire("hot", null).isAllowed());

        // 阈值热更为 4（debounce=0 同步生效）：已计 3（被拒请求同样计数），第 4 次放行
        rules("hot", "[{\"id\":\"fw\",\"algorithm\":\"fixed-window\","
                + "\"windowMillis\":10000,\"threshold\":4}]");
        assertTrue(engine.acquire("hot", null).isAllowed());
        assertFalse(engine.acquire("hot", null).isAllowed());
    }

    @Test
    public void clockDrivesWindowRollover() {
        rules("clock", "[{\"id\":\"fw\",\"algorithm\":\"fixed-window\","
                + "\"windowMillis\":1000,\"threshold\":1}]");
        assertTrue(engine.acquire("clock", null).isAllowed());
        assertFalse(engine.acquire("clock", null).isAllowed());
        kv.advanceMillis(1000);
        assertTrue(engine.acquire("clock", null).isAllowed());
    }

    // ------------------------------------------------- 辅助

    private void assertConfigError(String point) {
        try {
            engine.acquire(point, null);
            fail("expected RateLimitConfigException");
        } catch (RateLimitConfigException ignored) {
        }
    }

    public static class Ctx {

        private final String userId;

        Ctx(String userId) {
            this.userId = userId;
        }

        public String getUserId() {
            return userId;
        }
    }

    /**
     * 不实现任何能力的纯值域存储（用于能力校验测试）
     */
    private static class PlainValueStore implements KvStore {

        private final InMemoryKvStore delegate = new InMemoryKvStore();

        @Override
        public KvRecord get(SpaceKey key) {
            return delegate.get(key);
        }

        @Override
        public boolean put(SpaceKey key, KvRecord record, PutMode mode) {
            return delegate.put(key, record, mode);
        }

        @Override
        public boolean remove(SpaceKey key) {
            return delegate.remove(key);
        }

        @Override
        public boolean expire(SpaceKey key, long ttlMillis) {
            return delegate.expire(key, ttlMillis);
        }
    }
}
