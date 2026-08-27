package com.team4u.framework.kv.redis;

import com.team4u.framework.kv.CasCapable;
import com.team4u.framework.kv.CounterCapable;
import com.team4u.framework.kv.KvRecord;
import com.team4u.framework.kv.KvStore;
import com.team4u.framework.kv.KvStoreException;
import com.team4u.framework.kv.NativeTtlCapable;
import com.team4u.framework.kv.PutMode;
import com.team4u.framework.kv.ScanCapable;
import com.team4u.framework.kv.SpaceKey;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Redis 键值存储
 * <p>
 * 基于 {@link StringRedisTemplate}，物理键为 {@code space:key}（与
 * {@link SpaceKey#toString()} 一致）。充分利用 Redis 原生能力：
 * </p>
 * <ul>
 *     <li><b>原生 TTL</b>（实现 {@link NativeTtlCapable}）：过期由 Redis 淘汰，
 *     清理器自动跳过</li>
 *     <li><b>SETNX 原子写</b>：{@code put(IF_ABSENT)} 基于
 *     {@code setIfAbsent(key, value, timeout)}</li>
 *     <li><b>Lua CAS</b>（实现 {@link CasCapable}）：单脚本原子完成
 *     「比较值 → 替换/删除」，是分布式锁的可靠底座</li>
 *     <li><b>INCRBY 原子计数</b>（实现 {@link CounterCapable}）：
 *     计数键与普通值键共享物理键空间，同一键不可混用两种语义</li>
 *     <li><b>SCAN 扫描</b>：{@code scan(space)} 以 SCAN 游标遍历
 *     {@code space:*} 前缀（不使用阻塞的 KEYS）</li>
 * </ul>
 * 过期精度契约：{@code get} 以 {@code GET + PTTL} 还原记录的精确
 * {@code expireAt}（PTTL 为 -1 时视为永不过期）。
 *
 * @author jay.wu
 */
public class RedisKvStore implements KvStore, CasCapable, ScanCapable, NativeTtlCapable,
        CounterCapable, AutoCloseable {

    /**
     * 值匹配则替换（ARGV1=期望值, ARGV2=新值, ARGV3=新TTL毫秒，0 为持久化）
     */
    private static final RedisScript<Long> CAS_SET_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then "
                    + "if tonumber(ARGV[3]) > 0 then "
                    + "redis.call('SET', KEYS[1], ARGV[2], 'PX', ARGV[3]) "
                    + "else redis.call('SET', KEYS[1], ARGV[2]) end "
                    + "return 1 "
                    + "else return 0 end",
            Long.class);

    /**
     * 值匹配则删除（ARGV1=期望值）
     */
    private static final RedisScript<Long> CAS_REMOVE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('DEL', KEYS[1]) "
                    + "else return 0 end",
            Long.class);

    private final StringRedisTemplate redis;
    private final String keyPrefix;
    private final Clock clock;

    public RedisKvStore(StringRedisTemplate redis) {
        this(redis, "", Clock.systemUTC());
    }

    public RedisKvStore(StringRedisTemplate redis, String keyPrefix, Clock clock) {
        this.redis = Objects.requireNonNull(redis, "redis");
        this.keyPrefix = keyPrefix == null ? "" : keyPrefix;
        this.clock = clock;
    }

    @Override
    public KvRecord get(SpaceKey key) {
        try {
            String physicalKey = physical(key);
            String value = redis.opsForValue().get(physicalKey);
            if (value == null) {
                return null;
            }
            Long ttlMillis = redis.getExpire(physicalKey, TimeUnit.MILLISECONDS);
            if (ttlMillis == null || ttlMillis < 0) {
                // -1：无过期；-2：键不存在（GET 与 PTTL 间被删除，视为不存在）
                return ttlMillis != null && ttlMillis == -2 ? null : KvRecord.of(value);
            }
            return KvRecord.ofRaw(value, clock.millis() + ttlMillis);
        } catch (DataAccessException e) {
            throw new KvStoreException("Get failed|key=" + key, e);
        }
    }

    @Override
    public boolean put(SpaceKey key, KvRecord record, PutMode mode) {
        Objects.requireNonNull(record, "record");
        String physicalKey = physical(key);
        try {
            if (mode == PutMode.IF_ABSENT) {
                Boolean success = record.canExpire()
                        ? redis.opsForValue().setIfAbsent(physicalKey, record.getValue(),
                        record.getExpireAt() - clock.millis(), TimeUnit.MILLISECONDS)
                        : redis.opsForValue().setIfAbsent(physicalKey, record.getValue());
                return Boolean.TRUE.equals(success);
            }
            if (record.canExpire()) {
                redis.opsForValue().set(physicalKey, record.getValue(),
                        record.getExpireAt() - clock.millis(), TimeUnit.MILLISECONDS);
            } else {
                redis.opsForValue().set(physicalKey, record.getValue());
            }
            return true;
        } catch (DataAccessException e) {
            throw new KvStoreException("Put failed|key=" + key + "|mode=" + mode, e);
        }
    }

    @Override
    public boolean remove(SpaceKey key) {
        try {
            Boolean removed = redis.delete(physical(key));
            return Boolean.TRUE.equals(removed);
        } catch (DataAccessException e) {
            throw new KvStoreException("Remove failed|key=" + key, e);
        }
    }

    @Override
    public boolean expire(SpaceKey key, long ttlMillis) {
        try {
            String physicalKey = physical(key);
            if (ttlMillis <= 0) {
                // 对齐 KvStore 契约：非正 TTL 表示改为永不过期（PERSIST 语义）
                return Boolean.TRUE.equals(redis.persist(physicalKey));
            }
            return Boolean.TRUE.equals(redis.expire(physicalKey, ttlMillis, TimeUnit.MILLISECONDS));
        } catch (DataAccessException e) {
            throw new KvStoreException("Expire failed|key=" + key, e);
        }
    }

    @Override
    public boolean compareAndSet(SpaceKey key, String expectedValue, KvRecord update) {
        Objects.requireNonNull(update, "update");
        long ttlMillis = update.canExpire() ? update.getExpireAt() - clock.millis() : 0;
        Long result = redis.execute(CAS_SET_SCRIPT,
                java.util.Collections.singletonList(physical(key)),
                expectedValue, update.getValue(), Long.toString(Math.max(0, ttlMillis)));        return result != null && result == 1;
    }

    @Override
    public boolean compareAndRemove(SpaceKey key, String expectedValue) {
        try {
            Long result = redis.execute(CAS_REMOVE_SCRIPT,
                    java.util.Collections.singletonList(physical(key)),
                    expectedValue);
            return result != null && result == 1;
        } catch (DataAccessException e) {
            throw new KvStoreException("CompareAndRemove failed|key=" + key, e);
        }
    }

    @Override
    public long incrementAndGet(SpaceKey key, long delta) {
        try {
            Long value = redis.opsForValue().increment(physical(key), delta);
            if (value == null) {
                throw new KvStoreException("Increment returned null|key=" + key);
            }
            return value;
        } catch (DataAccessException e) {
            throw new KvStoreException("IncrementAndGet failed|key=" + key, e);
        }
    }

    @Override
    public List<SpaceKey> scan(String space) {
        List<SpaceKey> keys = new ArrayList<>();
        String pattern = keyPrefix + space + ":*";
        try (Cursor<String> cursor = redis.scan(
                ScanOptions.scanOptions().match(pattern).count(500).build())) {
            while (cursor.hasNext()) {
                String physicalKey = cursor.next();
                keys.add(decode(physicalKey));
            }
        } catch (DataAccessException e) {
            throw new KvStoreException("Scan failed|space=" + space, e);
        }
        return keys;
    }

    /**
     * 原生 TTL 存储无过期残留可清理（实现 {@link NativeTtlCapable}），
     * 清理器也不会调用本方法，恒返回 0
     */
    @Override
    public int pruneExpired(String space, int maxBatch) {
        return 0;
    }

    @Override
    public void close() {
        // RedisTemplate 生命周期由调用方管理
    }

    private String physical(SpaceKey key) {
        return keyPrefix + key;
    }

    /**
     * 物理键还原为 SpaceKey：去掉前缀后按第一个 ':' 切分
     */
    private SpaceKey decode(String physicalKey) {
        String s = keyPrefix.isEmpty() ? physicalKey
                : physicalKey.substring(keyPrefix.length());
        int idx = s.indexOf(':');
        return SpaceKey.of(s.substring(0, idx), s.substring(idx + 1));
    }
}
