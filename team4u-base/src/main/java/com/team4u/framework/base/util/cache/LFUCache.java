package com.team4u.framework.base.util.cache;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * LFU (Least Frequently Used) 缓存实现
 * <p>
 * 采用最少访问次数优先淘汰策略；当访问次数相同时，淘汰该频次桶中最久未访问的条目。
 * </p>
 *
 * @param <K> 缓存键（Key）的类型
 * @param <V> 缓存值（Value）的类型
 */
public class LFUCache<K, V> implements Cache<K, V> {

    private final int capacity;
    private final Map<K, Node<K, V>> nodes = new HashMap<>();
    private final Map<Integer, LinkedHashSet<K>> frequencyBuckets = new HashMap<>();
    private final ReentrantLock lock = new ReentrantLock();
    private int minFrequency = 0;

    public LFUCache(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be greater than 0");
        }
        this.capacity = capacity;
    }

    @Override
    public V get(K key) {
        lock.lock();
        try {
            Node<K, V> node = nodes.get(key);
            if (node == null) {
                return null;
            }
            increaseFrequency(node);
            return node.value;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void put(K key, V value) {
        lock.lock();
        try {
            Node<K, V> node = nodes.get(key);
            if (node != null) {
                node.value = value;
                increaseFrequency(node);
                return;
            }

            if (nodes.size() >= capacity) {
                evictLeastFrequentlyUsed();
            }

            Node<K, V> newNode = new Node<>(key, value);
            nodes.put(key, newNode);
            frequencyBuckets.computeIfAbsent(1, ignored -> new LinkedHashSet<>()).add(key);
            minFrequency = 1;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void remove(K key) {
        lock.lock();
        try {
            Node<K, V> node = nodes.remove(key);
            if (node == null) {
                return;
            }

            LinkedHashSet<K> keys = frequencyBuckets.get(node.frequency);
            if (keys != null) {
                keys.remove(key);
                if (keys.isEmpty()) {
                    frequencyBuckets.remove(node.frequency);
                    if (minFrequency == node.frequency) {
                        resetMinFrequency();
                    }
                }
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void clear() {
        lock.lock();
        try {
            nodes.clear();
            frequencyBuckets.clear();
            minFrequency = 0;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int size() {
        lock.lock();
        try {
            return nodes.size();
        } finally {
            lock.unlock();
        }
    }

    private void increaseFrequency(Node<K, V> node) {
        int oldFrequency = node.frequency;
        LinkedHashSet<K> oldBucket = frequencyBuckets.get(oldFrequency);
        if (oldBucket != null) {
            oldBucket.remove(node.key);
            if (oldBucket.isEmpty()) {
                frequencyBuckets.remove(oldFrequency);
                if (minFrequency == oldFrequency) {
                    minFrequency = oldFrequency + 1;
                }
            }
        }

        node.frequency++;
        frequencyBuckets.computeIfAbsent(node.frequency, ignored -> new LinkedHashSet<>()).add(node.key);
    }

    private void evictLeastFrequentlyUsed() {
        LinkedHashSet<K> keys = frequencyBuckets.get(minFrequency);
        if (keys == null || keys.isEmpty()) {
            resetMinFrequency();
            keys = frequencyBuckets.get(minFrequency);
        }
        if (keys == null || keys.isEmpty()) {
            return;
        }

        Iterator<K> iterator = keys.iterator();
        K evictedKey = iterator.next();
        iterator.remove();
        if (keys.isEmpty()) {
            frequencyBuckets.remove(minFrequency);
        }
        nodes.remove(evictedKey);
    }

    private void resetMinFrequency() {
        int nextMinFrequency = Integer.MAX_VALUE;
        for (Integer frequency : frequencyBuckets.keySet()) {
            if (frequency < nextMinFrequency) {
                nextMinFrequency = frequency;
            }
        }
        minFrequency = nextMinFrequency == Integer.MAX_VALUE ? 0 : nextMinFrequency;
    }

    private static final class Node<K, V> {
        private final K key;
        private V value;
        private int frequency = 1;

        private Node(K key, V value) {
            this.key = key;
            this.value = value;
        }
    }
}
