package com.team4u.framework.id.core;

import com.team4u.framework.config.test.TestConfigContext;
import com.team4u.framework.id.api.SeqConfigException;
import com.team4u.framework.id.group.GroupKeyPolicies;
import com.team4u.framework.id.group.GroupKeyPolicy;
import com.team4u.framework.id.group.SeqGroupConfig;
import com.team4u.framework.id.store.SeqStores;
import com.team4u.framework.kv.CounterCapable;
import com.team4u.framework.kv.KvRecord;
import com.team4u.framework.kv.KvStore;
import com.team4u.framework.kv.KvStores;
import com.team4u.framework.kv.PutMode;
import com.team4u.framework.kv.SpaceKey;
import com.team4u.framework.kv.memory.InMemoryKvStore;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

/**
 * 序号服务单元测试：规则校验、存储路由、热更新、自定义分组与格式化边界
 *
 * @author jay.wu
 */
public class SequenceServiceTest {

    private TestConfigContext config;
    private InMemoryKvStore store;
    private GroupKeyPolicies groupPolicies;
    private SequenceService service;

    @Before
    public void setUp() {
        config = TestConfigContext.create();
        store = new InMemoryKvStore();
        groupPolicies = new GroupKeyPolicies();
        service = new SequenceService(config.getConfigManager(), store,
                SequenceService.DEFAULT_CONFIG_PATTERN, SequenceService.DEFAULT_SPACE,
                groupPolicies.registry(), CLOCK, 1024);
    }

    @After
    public void tearDown() {
        service.destroy();
        config.destroy();
        store.close();
    }

    /**
     * 固定时钟：1970-01-01
     */
    private static final java.time.Clock CLOCK = java.time.Clock.fixed(
            java.time.Instant.EPOCH, java.time.ZoneId.of("UTC"));

    private void rule(String name, String json) {
        config.put("seq." + name, json);
    }

    // ------------------------------------------------- 规则校验

    @Test
    public void invalidStepRejected() {
        rule("bad", "{\"step\":0}");
        assertConfigError("bad");
    }

    @Test
    public void invalidSegmentRejected() {
        rule("bad", "{\"segment\":-1}");
        assertConfigError("bad");
    }

    @Test
    public void maxValueBelowStartRejected() {
        rule("bad", "{\"start\":100,\"maxValue\":99}");
        assertConfigError("bad");
    }

    @Test
    public void invalidJsonRejected() {
        rule("bad", "{not-json");
        assertConfigError("bad");
    }

    @Test
    public void invalidNameRejected() {
        try {
            service.next("a:b");
            fail("expected SeqConfigException");
        } catch (SeqConfigException ignored) {
        }
        try {
            service.next(null);
            fail("expected SeqConfigException");
        } catch (SeqConfigException ignored) {
        }
    }

    private void assertConfigError(String name) {
        try {
            service.next(name);
            fail("expected SeqConfigException");
        } catch (SeqConfigException ignored) {
        }
    }

    // ------------------------------------------------- 存储路由

    @Test
    public void storeWithoutCounterCapabilityRejected() {
        service = new SequenceService(config.getConfigManager(), new PlainValueStore(),
                SequenceService.DEFAULT_CONFIG_PATTERN, SequenceService.DEFAULT_SPACE,
                groupPolicies.registry(), CLOCK, 1024);
        rule("x", "{}");
        assertConfigError("x");
    }

    @Test
    public void namedStoreRoutesToItsOwnCounter() {
        InMemoryKvStore redisLike = new InMemoryKvStore();
        SeqStores.global().register("redisLike", redisLike);
        rule("default", "{}");
        rule("fast", "{\"store\":\"redisLike\"}");

        assertEquals(1, service.next("default"));
        assertEquals(1, service.next("fast"));

        CounterCapable fastCounter = KvStores.capabilityOf(redisLike, CounterCapable.class);
        assertEquals(1, fastCounter.incrementAndGet(SpaceKey.of("seq", "fast"), 0, 0));

        redisLike.close();
    }

    @Test
    public void unregisteredStoreRejected() {
        rule("bad", "{\"store\":\"missing\"}");
        assertConfigError("bad");
    }

    /**
     * 不实现 StoreWrapper 也不实现 CounterCapable 的纯值域存储
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

    // ------------------------------------------------- 规则热更新

    @Test
    public void ruleHotReloadTakesEffect() {
        rule("hot", "{}");
        assertEquals(1, service.next("hot"));
        assertEquals(2, service.next("hot"));

        // 补加上限：计数已到 2，maxValue=3 意味着还能发 1 个
        rule("hot", "{\"maxValue\":3}");
        assertEquals(3, service.next("hot"));
        assertNull(service.tryNext("hot"));
    }

    // ------------------------------------------------- 分组

    @Test
    public void groupPolicyMissingRejected() {
        rule("bad", "{\"group\":{\"type\":\"NOT_EXIST\"}}");
        assertConfigError("bad");
    }

    @Test
    public void groupKeyContainingColonRejected() {
        rule("bad", "{\"group\":{\"type\":\"EXT\",\"extKey\":\"merchantId\"}}");
        try {
            service.next("bad", Collections.singletonMap("merchantId", "M:001"));
            fail("expected SeqConfigException");
        } catch (SeqConfigException ignored) {
        }
    }

    @Test
    public void extGroupWithoutExtKeyRejected() {
        rule("bad", "{\"group\":{\"type\":\"EXT\"}}");
        assertConfigError("bad");
    }

    @Test
    public void customGroupPolicyWithAttrs() {
        groupPolicies.register(new GroupKeyPolicy() {

            @Override
            public String key() {
                return "MERCHANT";
            }

            @Override
            public String groupKey(Context context) {
                String prefix = context.getConfig().getAttrs().get("prefix");
                return prefix + context.getExt().get("merchantId");
            }
        });
        rule("m", "{\"group\":{\"type\":\"MERCHANT\",\"attrs\":{\"prefix\":\"MERCHANT-\"}}}");

        Map<String, Object> ext = new HashMap<>();
        ext.put("merchantId", "M001");
        assertEquals(1, service.next("m", ext));
        assertEquals(2, service.next("m", ext));
        ext.put("merchantId", "M002");
        assertEquals(1, service.next("m", ext));
    }

    // ------------------------------------------------- 取值与格式化

    @Test
    public void stepAndMaxValueArithmetic() {
        rule("big", "{\"start\":1000,\"step\":100,\"maxValue\":1200}");
        assertEquals(1000, service.next("big"));
        assertEquals(1100, service.next("big"));
        assertEquals(1200, service.next("big"));
        assertNull(service.tryNext("big"));
    }

    @Test
    public void formatWithAllVariables() {
        rule("tpl", "{\"seqLength\":4,\"format\":\"${name}-${group}-${seq}\"}");
        assertEquals("tpl--0001", service.nextFormatted("tpl"));
    }

    @Test
    public void padShorterThanValueIgnored() {
        rule("p", "{\"seqLength\":2}");
        rule("q", "{\"start\":1234567,\"seqLength\":4}");
        assertEquals("01", service.nextFormatted("p"));
        assertEquals("1234567", service.nextFormatted("q"));
    }
}
