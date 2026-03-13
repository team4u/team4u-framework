package com.team4u.framework.base.util.cache;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 定时自动过期的缓存实现
 * <p>
 * 为每个缓存条目设置统一的有效时长（Timeout）。
 * 采用惰性删除策略，即在获取缓存条目时检查其是否已过期。
 * 底层基于 {@link ConcurrentHashMap} 实现，保证了基础操作的并发安全性。
 * </p>
 *
 * @param <K> 缓存键（Key）的类型
 * @param <V> 缓存值（Value）的类型
 * @author jay.wu
 */
public class TimedCache<K, V> implements Cache<K, V> {

    private final Map<K, CacheObj<V>> map = new ConcurrentHashMap<>();
    private final long timeout;

    /**
     * 创建具有指定超时时长的定时缓存
     *
     * @param timeout 缓存项的有效时长（单位：毫秒）。若该值小于或等于 0，则表示永不过期。
     */
    public TimedCache(long timeout) {
        this.timeout = timeout;
    }

    @Override
    public V get(K key) {
        CacheObj<V> obj = map.get(key);
        if (obj == null) {
            return null;
        }
        // 获取时进行过期检查（惰性清理）
        if (obj.isExpired()) {
            map.remove(key);
            return null;
        }
        return obj.getValue();
    }

    @Override
    public void put(K key, V value) {
        map.put(key, new CacheObj<>(value, timeout));
    }

    @Override
    public void remove(K key) {
        map.remove(key);
    }

    @Override
    public void clear() {
        map.clear();
    }

    @Override
    public int size() {
        return map.size();
    }

    /**
     * 内部缓存包装类，用于存储值及其过期时间点
     *
     * @param <V> 包装值的类型
     */
    private static class CacheObj<V> {
        private final V value;
        private final long expireTime;

        /**
         * @param value   实际缓存的值
         * @param timeout 自当前时间起算的超时时长（毫秒）
         */
        public CacheObj(V value, long timeout) {
            this.value = value;
            this.expireTime = timeout > 0 ? System.currentTimeMillis() + timeout : Long.MAX_VALUE;
        }

        public V getValue() {
            return value;
        }

        /**
         * 判断当前缓存项是否已过期
         *
         * @return true 若当前系统时间已超过预设的过期时间点
         */
        public boolean isExpired() {
            return System.currentTimeMillis() > expireTime;
        }
    }
}
