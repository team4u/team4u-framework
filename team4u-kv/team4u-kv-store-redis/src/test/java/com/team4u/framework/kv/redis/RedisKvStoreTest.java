package com.team4u.framework.kv.redis;

import com.team4u.framework.kv.KvRecord;
import com.team4u.framework.kv.KvStore;
import com.team4u.framework.kv.KvStoreException;
import com.team4u.framework.kv.NativeTtlCapable;
import com.team4u.framework.kv.PutMode;
import com.team4u.framework.kv.SpaceKey;
import com.team4u.framework.kv.test.TestKvContext.SettableClock;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
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
