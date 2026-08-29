package com.team4u.framework.kv.redis;

import com.team4u.framework.kv.CasCapable;
import com.team4u.framework.kv.CounterCapable;
import com.team4u.framework.kv.KvRecord;
import com.team4u.framework.kv.KvStore;
import com.team4u.framework.kv.KvStoreException;
import com.team4u.framework.kv.NativeTtlCapable;
import com.team4u.framework.kv.PutMode;
import com.team4u.framework.kv.ScanCapable;
import com.team4u.framework.kv.ScoredWindowCapable;
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
 *     TTL 大于 0 时经 Lua 脚本「INCRBY + 首次设置 PEXPIRE」原子生效；
 *     计数键与普通值键共享物理键空间，同一键不可混用两种语义</li>
 *     <li><b>ZSET 计分窗口</b>（实现 {@link ScoredWindowCapable}）：
 *     单 Lua 脚本原子完成「ZREMRANGEBYSCORE 裁剪 → ZCARD 计数 →
 *     条件 ZADD 准入 → PEXPIRE 刷新」，是滑动窗口限流的底座</li>
 *     <li><b>SCAN 扫描</b>：{@code scan(space)} 以 SCAN 游标遍历
 *     {@code space:*} 前缀（不使用阻塞的 KEYS）</li>
 * </ul>
 * 过期精度契约：{@code get} 以 {@code GET + PTTL} 还原记录的精确
 * {@code expireAt}（PTTL 为 -1 时视为永不过期）。
 *
 * @author jay.wu
 */
public class RedisKvStore implements KvStore, CasCapable, ScanCapable, NativeTtlCapable,
        CounterCapable, ScoredWindowCapable, AutoCloseable {

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

    /**
     * 值匹配则续期（保序，ARGV1=期望值, ARGV2=新TTL毫秒，0 为改为永不过期）：
     * 仅当新 TTL 晚于当前剩余 TTL 时才刷新——晚到的续约不缩短租约；
     * 当前已永不过期（PTTL 为 -1）则保持不变；校验与更新单脚本原子生效
     */
    private static final RedisScript<Long> CAS_EXPIRE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then "
                    + "local pttl = redis.call('PTTL', KEYS[1]) "
                    + "if pttl == -1 then return 1 end "
                    + "if tonumber(ARGV[2]) == 0 then "
                    + "redis.call('PERSIST', KEYS[1]) return 1 end "
                    + "if tonumber(ARGV[2]) > pttl then "
                    + "redis.call('PEXPIRE', KEYS[1], ARGV[2]) end "
                    + "return 1 "
                    + "else return 0 end",
            Long.class);

    /**
     * 带 TTL 的原子递增（ARGV1=递增量, ARGV2=TTL毫秒）：
     * 仅当键当前无 TTL（PTTL 为 -1，含新建键与存量无 TTL 键）时设置 PEXPIRE，
     * 后续递增不刷新 TTL；TTL 与递增在脚本内原子生效
     */
    private static final RedisScript<Long> INCREMENT_TTL_SCRIPT = new DefaultRedisScript<>(
            "local v = redis.call('INCRBY', KEYS[1], ARGV[1]) "
                    + "if tonumber(ARGV[2]) > 0 and redis.call('PTTL', KEYS[1]) == -1 then "
                    + "redis.call('PEXPIRE', KEYS[1], ARGV[2]) "
                    + "end "
                    + "return v",
            Long.class);

    /**
     * 计分窗口原子操作（KEYS[1]=键; ARGV[1]=cutoff, ARGV[2]=score,
     * ARGV[3]=maxCount, ARGV[4]=ttl, ARGV[5..]=members）：
     * 裁剪（score &lt;= cutoff）→ 计数 → 超限不添加 → 添加 → 刷新 TTL，
     * 返回 {是否接受(0/1), 裁剪后计数, 最老成员 score（空串表示无成员）}
     */
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> WINDOW_OFFER_SCRIPT = new DefaultRedisScript<>(
            "redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', ARGV[1]) "
                    + "local count = redis.call('ZCARD', KEYS[1]) "
                    + "local n = #ARGV - 4 "
                    + "if n > 0 and count + n > tonumber(ARGV[3]) then "
                    + "local oldest = redis.call('ZRANGE', KEYS[1], 0, 0, 'WITHSCORES') "
                    + "if oldest[2] then return {0, count, oldest[2]} else return {0, count, ''} end "
                    + "end "
                    + "for i = 1, n do redis.call('ZADD', KEYS[1], ARGV[2], ARGV[4 + i]) end "
                    + "if tonumber(ARGV[4]) > 0 then redis.call('PEXPIRE', KEYS[1], ARGV[4]) end "
                    + "local oldest2 = redis.call('ZRANGE', KEYS[1], 0, 0, 'WITHSCORES') "
                    + "if oldest2[2] then return {1, count + n, oldest2[2]} else return {1, count + n, ''} end",
            List.class);

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

    /**
     * 单 Lua 脚本原子完成「值匹配」校验与保序续期；
     * 绝对过期时间转换为相对 TTL 后与剩余 PTTL 比较（与 {@link #compareAndSet}
     * 一致以客户端时钟折算）；陈旧请求（新过期时间早于当前时钟、折算 TTL 为负）
     * 落入「不大于剩余 TTL」分支自然成为无害空操作，不会把键钳成永不过期
     */
    @Override
    public boolean compareAndExpire(SpaceKey key, String expectedValue, long newExpireAtMillis) {
        long newTtlMillis = newExpireAtMillis == 0
                ? 0 : newExpireAtMillis - clock.millis();
        try {
            Long result = redis.execute(CAS_EXPIRE_SCRIPT,
                    java.util.Collections.singletonList(physical(key)),
                    expectedValue, Long.toString(newTtlMillis));
            return result != null && result == 1;
        } catch (DataAccessException e) {
            throw new KvStoreException("CompareAndExpire failed|key=" + key, e);
        }
    }

    @Override
    public long incrementAndGet(SpaceKey key, long delta, long ttlMillis) {
        try {
            Long value;
            if (ttlMillis <= 0) {
                // 永不过期：直接 INCRBY（原语义）
                value = redis.opsForValue().increment(physical(key), delta);
            } else {
                // TTL 与递增经脚本原子生效，PTTL == -1 覆盖存量无 TTL 键
                value = redis.execute(INCREMENT_TTL_SCRIPT,
                        java.util.Collections.singletonList(physical(key)),
                        Long.toString(delta), Long.toString(ttlMillis));
            }
            if (value == null) {
                throw new KvStoreException("Increment returned null|key=" + key);
            }
            return value;
        } catch (DataAccessException e) {
            throw new KvStoreException("IncrementAndGet failed|key=" + key, e);
        }
    }

    @SuppressWarnings("rawtypes")
    @Override
    public Verdict offer(SpaceKey key, Offer offer) {
        Objects.requireNonNull(offer, "offer");
        List<String> members = offer.getMembers() == null
                ? java.util.Collections.emptyList()
                : offer.getMembers();
        List<Object> args = new ArrayList<>(members.size() + 4);
        args.add(Long.toString(offer.getCutoffScore()));
        args.add(Long.toString(offer.getMemberScore()));
        args.add(Integer.toString(offer.getMaxCount()));
        args.add(Long.toString(offer.getTtlMillis()));
        args.addAll(members);
        try {
            List result = redis.execute(WINDOW_OFFER_SCRIPT,
                    java.util.Collections.singletonList(physical(key)),
                    args.toArray());
            return parseVerdict(key, result);
        } catch (DataAccessException e) {
            throw new KvStoreException("Offer failed|key=" + key, e);
        }
    }

    /**
     * 解析窗口脚本返回的 {是否接受, 裁剪后计数, 最老成员 score}：
     * 元素转字符串后解析数值，最老成员为空串表示窗口为空（oldestScore = null）
     */
    private static Verdict parseVerdict(SpaceKey key, List result) {
        if (result == null || result.size() < 3) {
            throw new KvStoreException("Offer returned invalid result|key=" + key
                    + "|result=" + result);
        }
        long accepted = Long.parseLong(String.valueOf(result.get(0)));
        long count = Long.parseLong(String.valueOf(result.get(1)));
        String oldest = String.valueOf(result.get(2));
        Long oldestScore = oldest.isEmpty() ? null : (long) Double.parseDouble(oldest);
        return Verdict.builder()
                .accepted(accepted == 1)
                .count(count)
                .oldestScore(oldestScore)
                .build();
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
