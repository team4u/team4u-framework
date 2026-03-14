package com.team4u.framework.base.cache;

/**
 * 缓存基础接口
 * <p>
 * 提供 KV 缓存的标准操作定义，包含基本的读写、删除、清理和容量获取功能。
 * </p>
 *
 * @param <K> 缓存键（Key）的类型
 * @param <V> 缓存值（Value）的类型
 * @author jay.wu
 */
public interface Cache<K, V> {

    /**
     * 根据键获取缓存内容
     *
     * @param key 缓存键
     * @return 对应的缓存值；若键不存在或已过期，则返回 null
     */
    V get(K key);

    /**
     * 将键值对存入缓存
     *
     * @param key   缓存键
     * @param value 缓存值
     */
    void put(K key, V value);

    /**
     * 根据键从缓存中移除指定内容
     *
     * @param key 缓存键
     */
    void remove(K key);

    /**
     * 清空缓存中所有内容
     */
    void clear();

    /**
     * 获取当前缓存中的条目数量
     *
     * @return 缓存条目总数
     */
    int size();
}
