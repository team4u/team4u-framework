package com.team4u.framework.base.util;

import com.team4u.framework.base.util.cache.Cache;
import com.team4u.framework.base.util.cache.LRUCache;
import com.team4u.framework.base.util.cache.TimedCache;

/**
 * 缓存工具类
 * <p>
 * 提供各种常用缓存策略的实例化工厂方法，包括最近最少使用（LRU）缓存和超时过期缓存。
 *
 * @author jay.wu
 */
public class CacheUtil {

    /**
     * 创建一个新的 LRU（最近最少使用）缓存
     *
     * @param capacity 缓存最大容量
     * @param <K>      键类型
     * @param <V>      值类型
     * @return LRU 缓存实例
     */
    public static <K, V> Cache<K, V> newLRUCache(int capacity) {
        return new LRUCache<>(capacity);
    }

    /**
     * 创建一个新的 LFU（最不经常使用）缓存
     * <p>
     * 注意：当前实现暂以 LRU 代替 LFU。
     *
     * @param capacity 缓存最大容量
     * @param <K>      键类型
     * @param <V>      值类型
     * @return LFU 缓存实例
     */
    public static <K, V> Cache<K, V> newLFUCache(int capacity) {
        return new LRUCache<>(capacity);
    }

    /**
     * 创建一个新的具有超时时效的缓存
     *
     * @param timeout 默认超时时间（毫秒）
     * @param <K>     键类型
     * @param <V>     值类型
     * @return 超时缓存实例
     */
    public static <K, V> TimedCache<K, V> newTimedCache(long timeout) {
        return new TimedCache<>(timeout);
    }
}
