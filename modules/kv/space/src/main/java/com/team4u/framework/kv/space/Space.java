package com.team4u.framework.kv.space;

import com.team4u.framework.kv.KvRecord;
import com.team4u.framework.kv.KvStore;
import com.team4u.framework.kv.PutMode;
import com.team4u.framework.kv.SpaceKey;
import com.team4u.framework.serializer.json.JsonUtil;

import java.time.Clock;
import java.util.Objects;

/**
 * 类型化键空间门面：绑定键空间与值类型，读写自动序列化/反序列化
 * <p>
 * 通过 {@link Spaces#use(String, KvStore)} 按注册的策略构建，
 * 业务代码不再传递 {@code type} 字符串与值类型参数。
 * 值经 {@link JsonUtil} 序列化为字符串存储，支持任意 JSON 兼容类型。
 * </p>
 *
 * @param <V> 值类型
 * @author jay.wu
 */
public class Space<V> {

    private final KvStore store;
    private final SpacePolicy policy;
    private final Class<V> valueType;
    private final Clock clock;

    @SuppressWarnings("unchecked")
    Space(KvStore store, SpacePolicy policy, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.clock = clock;
        this.valueType = (Class<V>) policy.getValueType();
    }

    /**
     * 键空间名
     */
    public String name() {
        return policy.getName();
    }

    /**
     * 读取值（使用默认时钟判定过期由存储负责）
     *
     * @return 值；键不存在或已过期返回 {@code null}
     */
    public V get(String key) {
        KvRecord record = store.get(SpaceKey.of(policy.getName(), key));
        return record == null ? null : JsonUtil.toBean(record.getValue(), valueType);
    }

    /**
     * 写入值，使用策略的默认有效期
     */
    public void put(String key, V value) {
        put(key, value, policy.getDefaultTtlMillis());
    }

    /**
     * 写入值并指定有效期
     *
     * @param ttlMillis 有效期（毫秒），0 为永不过期
     */
    public void put(String key, V value, long ttlMillis) {
        store.put(SpaceKey.of(policy.getName(), key),
                KvRecord.of(JsonUtil.toJsonStr(value), ttlMillis, clock.millis()),
                PutMode.SET);
    }

    /**
     * 仅当键不存在时写入（SETNX 语义），适合幂等控制
     *
     * @return {@code true} 抢占成功；键已存在返回 {@code false}
     */
    public boolean putIfAbsent(String key, V value, long ttlMillis) {
        return store.put(SpaceKey.of(policy.getName(), key),
                KvRecord.of(JsonUtil.toJsonStr(value), ttlMillis, clock.millis()),
                PutMode.IF_ABSENT);
    }

    /**
     * 删除键
     *
     * @return {@code true} 表示删除了存在且未过期的键
     */
    public boolean remove(String key) {
        return store.remove(SpaceKey.of(policy.getName(), key));
    }

    /**
     * 为已存在的键设置新的有效期
     *
     * @return {@code true} 续期成功
     */
    public boolean expire(String key, long ttlMillis) {
        return store.expire(SpaceKey.of(policy.getName(), key), ttlMillis);
    }
}
