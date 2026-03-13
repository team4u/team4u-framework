package com.team4u.framework.base.util.cache;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LRU (Least Recently Used) 缓存实现
 * <p>
 * 基于 {@link LinkedHashMap} 实现的最近最少使用缓存策略。
 * 当缓存达到最大容量时，将自动移除最长时间未被访问的条目。
 * 本类使用了 {@code synchronized} 关键词以确保线程安全。
 * </p>
 *
 * @param <K> 缓存键（Key）的类型
 * @param <V> 缓存值（Value）的类型
 * @author jay.wu
 */
public class LRUCache<K, V> implements Cache<K, V> {

    private final Map<K, V> map;
    private final int capacity;

    /**
     * 创建具有指定容量限制的 LRU 缓存
     *
     * @param capacity 最大允许存储的缓存项数量
     */
    public LRUCache(int capacity) {
        this.capacity = capacity;
        this.map = new LinkedHashMap<K, V>(capacity, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > LRUCache.this.capacity;
            }
        };
    }

    @Override
    public synchronized V get(K key) {
        return map.get(key);
    }

    @Override
    public synchronized void put(K key, V value) {
        map.put(key, value);
    }

    @Override
    public synchronized void remove(K key) {
        map.remove(key);
    }

    @Override
    public synchronized void clear() {
        map.clear();
    }

    @Override
    public synchronized int size() {
        return map.size();
    }
}
