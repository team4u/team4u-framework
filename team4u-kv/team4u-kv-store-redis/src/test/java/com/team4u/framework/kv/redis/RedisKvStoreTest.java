package com.team4u.framework.kv.redis;

import com.team4u.framework.kv.CounterCapable;
import com.team4u.framework.kv.KvRecord;
import com.team4u.framework.kv.KvStore;
import com.team4u.framework.kv.KvStoreException;
import com.team4u.framework.kv.NativeTtlCapable;
import com.team4u.framework.kv.PutMode;
import com.team4u.framework.kv.ScoredWindowCapable;
import com.team4u.framework.kv.ScoredWindowCapable.Offer;
import com.team4u.framework.kv.ScoredWindowCapable.Verdict;
import com.team4u.framework.kv.SpaceKey;
import com.team4u.framework.kv.test.TestKvContext.SettableClock;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 基于 Mockito 的 Redis 存储单元测试（行为映射正确性）。
 * 语义一致性由真实 Redis 环境跑契约测试保证。
 */
public class RedisKvStoreTest {

    private static final String KEY = "user:u1";

    private SettableClock clock;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private KvStore store;

    @SuppressWarnings("unchecked")
    @Before
    public void setUp() {
        clock = new SettableClock(0L);
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        store = new RedisKvStore(redis, "", clock);
    }

    @Test
    public void declaresNativeTtl() {
        assertTrue(store instanceof NativeTtlCapable);
    }

    @Test
    public void getCombinesValueAndPttl() {
        when(valueOps.get(KEY)).thenReturn("v1");
        when(redis.getExpire(KEY, TimeUnit.MILLISECONDS)).thenReturn(4000L);

        KvRecord record = store.get(SpaceKey.of("user", "u1"));

        assertEquals("v1", record.getValue());
        assertEquals(4000, record.getExpireAt()); // now(0) + pttl
    }

    @Test
    public void getWithoutTtlIsPermanent() {
        when(valueOps.get(KEY)).thenReturn("v1");
        when(redis.getExpire(KEY, TimeUnit.MILLISECONDS)).thenReturn(-1L);

        assertEquals(0, store.get(SpaceKey.of("user", "u1")).getExpireAt());
    }

    @Test
    public void getMissingKeyReturnsNull() {
        when(valueOps.get(KEY)).thenReturn(null);
        assertNull(store.get(SpaceKey.of("user", "u1")));
    }

    @Test
    public void putSetUsesPxBasedOnExpireAt() {
        store.put(SpaceKey.of("user", "u1"), KvRecord.of("v1", 5000, clock.millis()), PutMode.SET);

        verify(valueOps).set(eq(KEY), eq("v1"), eq(5000L), eq(TimeUnit.MILLISECONDS));
    }

    @Test
    public void putSetPermanentWithoutTtl() {
        store.put(SpaceKey.of("user", "u1"), KvRecord.of("v1"), PutMode.SET);

        verify(valueOps).set(KEY, "v1");
    }

    @Test
    public void putIfAbsentUsesSetnxWithTtl() {
        when(valueOps.setIfAbsent(KEY, "v1", 5000L, TimeUnit.MILLISECONDS)).thenReturn(true);

        assertTrue(store.put(SpaceKey.of("user", "u1"),
                KvRecord.of("v1", 5000, clock.millis()), PutMode.IF_ABSENT));
    }

    @Test
    public void removeReturnsDeletedCount() {
        when(redis.delete(anyString())).thenReturn(true);
        assertTrue(store.remove(SpaceKey.of("user", "u1")));

        when(redis.delete(anyString())).thenReturn(false);
        assertFalse(store.remove(SpaceKey.of("user", "u1")));
    }

    @Test
    public void expireMapsToRedisExpire() {
        when(redis.expire(KEY, 5000L, TimeUnit.MILLISECONDS)).thenReturn(true);
        assertTrue(store.expire(SpaceKey.of("user", "u1"), 5000));
    }

    @Test
    public void nonPositiveTtlMapsToPersist() {
        when(redis.persist(KEY)).thenReturn(true);
        assertTrue(store.expire(SpaceKey.of("user", "u1"), 0));
        verify(redis).persist(KEY);
    }

    @Test
    public void incrementAndGetMapsToIncrBy() {
        assertTrue(store instanceof CounterCapable);

        when(valueOps.increment(KEY, 5)).thenReturn(5L);
        assertEquals(5, ((CounterCapable) store).incrementAndGet(SpaceKey.of("user", "u1"), 5, 0));
        verify(valueOps).increment(KEY, 5);
    }

    @SuppressWarnings("unchecked")
    @Test
    public void incrementWithTtlInvokesScript() {
        when(redis.execute(any(RedisScript.class), anyList(), eq("5"), eq("1000")))
                .thenReturn(5L);

        assertEquals(5, ((CounterCapable) store)
                .incrementAndGet(SpaceKey.of("user", "u1"), 5, 1000));

        // 脚本参数：delta、TTL 毫秒（TTL 与递增原子生效，仅首设不刷新）
        verify(redis).execute(any(RedisScript.class), anyList(), eq("5"), eq("1000"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void incrementScriptFailureWrappedAsStoreException() {
        when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString()))
                .thenThrow(new QueryTimeoutException("timeout"));
        try {
            ((CounterCapable) store).incrementAndGet(SpaceKey.of("user", "u1"), 1, 1000);
            fail("expected KvStoreException");
        } catch (KvStoreException e) {
            assertTrue(e.getCause() instanceof QueryTimeoutException);
        }
    }

    @Test
    public void incrementFailureWrappedAsStoreException() {
        CounterCapable counter = (CounterCapable) store;
        when(valueOps.increment(anyString(), any(Long.class)))
                .thenThrow(new QueryTimeoutException("timeout"));
        try {
            counter.incrementAndGet(SpaceKey.of("user", "u1"), 1, 0);
            fail("expected KvStoreException");
        } catch (KvStoreException e) {
            assertTrue(e.getCause() instanceof QueryTimeoutException);
        }
    }

    // ------------------------------------------------- 计分窗口能力

    @Test
    public void declaresScoredWindowCapability() {
        assertTrue(store instanceof ScoredWindowCapable);
    }

    @SuppressWarnings("unchecked")
    @Test
    public void offerInvokesWindowScriptWithAssembledArgs() {
        when(redis.execute(any(RedisScript.class), anyList(),
                eq("90"), eq("100"), eq("2"), eq("0"), eq("m1"), eq("m2")))
                .thenReturn(Arrays.asList(1L, 2L, "100"));

        Verdict verdict = ((ScoredWindowCapable) store).offer(SpaceKey.of("rl", "w1"),
                Offer.builder()
                        .cutoffScore(90)
                        .memberScore(100)
                        .members(Arrays.asList("m1", "m2"))
                        .maxCount(2)
                        .ttlMillis(0)
                        .build());

        assertTrue(verdict.isAccepted());
        assertEquals(2, verdict.getCount());
        assertEquals(100L, verdict.getOldestScore().longValue());

        // 脚本参数：cutoff、score、maxCount、ttl、members...
        verify(redis).execute(any(RedisScript.class), anyList(),
                eq("90"), eq("100"), eq("2"), eq("0"), eq("m1"), eq("m2"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void offerParsesRejectionVerdict() {
        when(redis.execute(any(RedisScript.class), anyList(),
                eq("0"), eq("200"), eq("2"), eq("1000"), eq("m3")))
                .thenReturn(Arrays.asList(0L, 5L, "123"));

        Verdict verdict = ((ScoredWindowCapable) store).offer(SpaceKey.of("rl", "w1"),
                Offer.builder()
                        .cutoffScore(0)
                        .memberScore(200)
                        .members(Collections.singletonList("m3"))
                        .maxCount(2)
                        .ttlMillis(1000)
                        .build());

        assertFalse(verdict.isAccepted());
        assertEquals(5, verdict.getCount());
        assertEquals(123L, verdict.getOldestScore().longValue());
    }

    @SuppressWarnings("unchecked")
    @Test
    public void offerParsesEmptyOldestAsNull() {
        when(redis.execute(any(RedisScript.class), anyList(),
                eq("50"), eq("0"), eq("3"), eq("0")))
                .thenReturn(Arrays.asList(1L, 0L, ""));

        Verdict verdict = ((ScoredWindowCapable) store).offer(SpaceKey.of("rl", "w2"),
                Offer.builder()
                        .cutoffScore(50)
                        .maxCount(3)
                        .build());

        assertTrue(verdict.isAccepted());
        assertEquals(0, verdict.getCount());
        assertNull(verdict.getOldestScore());
    }

    @SuppressWarnings("unchecked")
    @Test
    public void offerFailureWrappedAsStoreException() {
        when(redis.execute(any(RedisScript.class), anyList(),
                any(Object[].class)))
                .thenThrow(new QueryTimeoutException("timeout"));
        try {
            ((ScoredWindowCapable) store).offer(SpaceKey.of("rl", "w1"),
                    Offer.builder().cutoffScore(0).maxCount(1).build());
            fail("expected KvStoreException");
        } catch (KvStoreException e) {
            assertTrue(e.getCause() instanceof QueryTimeoutException);
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    public void compareAndSetInvokesLuaScript() {
        when(redis.execute(any(RedisScript.class), anyList(),
                eq("token-a"), eq("token-b"), eq("5000")))
                .thenReturn(1L);

        assertTrue(store instanceof com.team4u.framework.kv.CasCapable);
        boolean success = ((com.team4u.framework.kv.CasCapable) store).compareAndSet(
                SpaceKey.of("lock", "job"),
                "token-a",
                KvRecord.of("token-b", 5000, clock.millis()));

        assertTrue(success);

        ArgumentCaptor<Object> args = ArgumentCaptor.forClass(Object.class);
        // 脚本参数：期望值、新值、TTL
        verify(redis).execute(any(RedisScript.class), anyList(),
                args.capture(), args.capture(), args.capture());
    }

    @Test(expected = KvStoreException.class)
    public void scanFailureWrappedAsStoreException() {
        when(redis.scan(any())).thenThrow(
                new org.springframework.data.redis.RedisConnectionFailureException("down"));
        ((com.team4u.framework.kv.ScanCapable) store).scan("user");
    }

    @Test(expected = KvStoreException.class)
    public void infraFailureWrappedAsKvStoreException() {
        when(redis.opsForValue()).thenThrow(
                new org.springframework.data.redis.RedisConnectionFailureException("down"));
        store.get(SpaceKey.of("user", "u1"));
    }

    @Test
    public void casRejectsAlreadyExpiredUpdate() {
        // update 的过期时间早于当前时钟（时钟回拨/陈旧记录）：CAS 直接失败，
        // 不得把「本应立即过期」的写入钳成永不过期键
        clock.advance(1000);
        KvRecord staleUpdate = KvRecord.ofRaw("token-b", 500);   // expireAt < now

        boolean success = ((com.team4u.framework.kv.CasCapable) store).compareAndSet(
                SpaceKey.of("lock", "job"), "token-a", staleUpdate);

        assertFalse(success);
    }

    @SuppressWarnings("unchecked")
    @Test
    public void compareAndExpireInvokesLuaScriptWithTtl() {
        // 新过期时间 5000ms，当前时钟 1000：折算 TTL = 4000
        clock.advance(1000);
        when(redis.execute(any(RedisScript.class), anyList(), eq("token-a"), eq("4000")))
                .thenReturn(1L);

        boolean success = ((com.team4u.framework.kv.CasCapable) store).compareAndExpire(
                SpaceKey.of("lock", "job"), "token-a", 5000);

        assertTrue(success);
        // 脚本参数：期望值、折算后的相对 TTL
        verify(redis).execute(any(RedisScript.class), anyList(),
                eq("token-a"), eq("4000"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void compareAndExpireZeroMapsToPersistTtl() {
        // newExpireAtMillis = 0 表示改为永不过期，脚本参数 TTL 传 0（脚本内 PERSIST）
        when(redis.execute(any(RedisScript.class), anyList(), eq("token-a"), eq("0")))
                .thenReturn(1L);

        assertTrue(((com.team4u.framework.kv.CasCapable) store).compareAndExpire(
                SpaceKey.of("lock", "job"), "token-a", 0));
        verify(redis).execute(any(RedisScript.class), anyList(),
                eq("token-a"), eq("0"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void compareAndExpireStaleDeadlineMapsToNonPositiveTtl() {
        // 陈旧请求：新过期时间早于当前时钟，折算 TTL 为负——落入脚本「不大于剩余 TTL」
        // 分支成为无害空操作，绝不会钳成永不过期
        clock.advance(2000);
        when(redis.execute(any(RedisScript.class), anyList(), eq("token-a"), eq("-1000")))
                .thenReturn(1L);

        assertTrue(((com.team4u.framework.kv.CasCapable) store).compareAndExpire(
                SpaceKey.of("lock", "job"), "token-a", 1000));
        verify(redis).execute(any(RedisScript.class), anyList(),
                eq("token-a"), eq("-1000"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void compareAndExpireFailureWrappedAsStoreException() {
        when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString()))
                .thenThrow(new QueryTimeoutException("timeout"));
        try {
            ((com.team4u.framework.kv.CasCapable) store).compareAndExpire(
                    SpaceKey.of("lock", "job"), "token-a", 5000);
            fail("expected KvStoreException");
        } catch (KvStoreException e) {
            assertTrue(e.getCause() instanceof QueryTimeoutException);
        }
    }

    @Test
    public void keyPrefixApplied() {
        RedisKvStore prefixed = new RedisKvStore(redis, "app1:", clock);
        when(valueOps.get("app1:user:u1")).thenReturn("v1");
        when(redis.getExpire("app1:user:u1", TimeUnit.MILLISECONDS)).thenReturn(-1L);

        assertEquals("v1", prefixed.get(SpaceKey.of("user", "u1")).getValue());
    }

    @Test(expected = NullPointerException.class)
    public void nullTemplateRejected() {
        new RedisKvStore(null);
    }
}
