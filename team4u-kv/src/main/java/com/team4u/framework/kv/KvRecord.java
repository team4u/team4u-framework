package com.team4u.framework.kv;

/**
 * 不可变的键值记录：值 + 过期语义
 * <p>
 * 过期时间戳 {@link #expireAt} 为 epoch 毫秒，{@code 0} 表示永不过期。
 * 记录不可变，{@link #expire(long, long)} 返回续期后的新实例。
 * </p>
 */
public final class KvRecord {

    private final String value;
    private final long expireAt;

    private KvRecord(String value, long expireAt) {
        this.value = value;
        this.expireAt = expireAt;
    }

    /**
     * 永不过期的记录
     */
    public static KvRecord of(String value) {
        return new KvRecord(value, 0L);
    }

    /**
     * 自指定时间起 ttl 毫秒后过期的记录
     *
     * @param ttlMillis 有效时长（毫秒），小于等于 0 视为永不过期
     * @param now       当前时间（epoch 毫秒）
     */
    public static KvRecord of(String value, long ttlMillis, long now) {
        return ttlMillis <= 0 ? of(value) : new KvRecord(value, now + ttlMillis);
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
        return new KvRecord(value, ttlMillis <= 0 ? 0L : now + ttlMillis);
    }

    @Override
    public String toString() {
        return "KvRecord{value='" + value + "', expireAt=" + expireAt + "}";
    }
}
