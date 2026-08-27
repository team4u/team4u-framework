package com.team4u.framework.kv;

import java.util.Objects;

/**
 * 不可变的键值记录：值 + 过期语义
 * <p>
 * 过期时间戳 {@link #getExpireAt} 为 epoch 毫秒，{@code 0} 表示永不过期。
 * 记录不可变，{@link #expire(long, long)} 返回续期后的新实例。
 * 值不允许为 {@code null}——“键不存在”以 {@code get} 返回 {@code null} 记录表达，
 * 值层面的空语义由调用方以空串等哨兵值自行约定。
 * </p>
 *
 * @author jay.wu
 */
public final class KvRecord {

    private final String value;
    private final long expireAt;

    private KvRecord(String value, long expireAt) {
        this.value = Objects.requireNonNull(value, "value");
        this.expireAt = expireAt;
    }

    /**
     * 永不过期的记录
     */
    public static KvRecord of(String value) {
        return new KvRecord(value, 0L);
    }

    /**
     * 按既有过期时间戳重建记录（存储实现反序列化用）
     *
     * @param expireAt epoch 毫秒，0 为永不过期
     */
    public static KvRecord ofRaw(String value, long expireAt) {
        return new KvRecord(value, expireAt < 0 ? 0L : expireAt);
    }

    /**
     * 计算过期时间戳：{@code ttlMillis <= 0} 返回 0（永不过期），溢出时饱和为极大值
     */
    public static long expireAtOf(long ttlMillis, long now) {
        if (ttlMillis <= 0) {
            return 0L;
        }
        long expireAt = now + ttlMillis;
        return expireAt < 0 ? Long.MAX_VALUE : expireAt;
    }

    /**
     * 自指定时间起 ttl 毫秒后过期的记录
     *
     * @param ttlMillis 有效时长（毫秒），小于等于 0 视为永不过期；溢出时饱和为极大值
     * @param now       当前时间（epoch 毫秒）
     */
    /**
     * 自指定时间起 ttl 毫秒后过期的记录
     *
     * @param ttlMillis 有效时长（毫秒），小于等于 0 视为永不过期；溢出时饱和为极大值
     * @param now       当前时间（epoch 毫秒）
     */
    public static KvRecord of(String value, long ttlMillis, long now) {
        return new KvRecord(value, expireAtOf(ttlMillis, now));
    }

    public String getValue() {
        return value;
    }

    /**
     * @return 过期时间戳（epoch 毫秒），0 为永不过期
     */
    public long getExpireAt() {
        return expireAt;
    }

    public boolean canExpire() {
        return expireAt > 0;
    }

    public boolean isExpired(long now) {
        return canExpire() && now >= expireAt;
    }

    /**
     * 返回自指定时间起 ttl 毫秒后过期的新记录（原记录不变）
     */
    public KvRecord expire(long ttlMillis, long now) {
        return of(value, ttlMillis, now);
    }

    @Override
    public String toString() {
        return "KvRecord{value='" + value + "', expireAt=" + expireAt + "}";
    }
}
