package com.team4u.base.instance;

import cn.hutool.cache.Cache;
import cn.hutool.cache.CacheUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.HashUtil;
import cn.hutool.core.util.StrUtil;
import com.team4u.base.config.ConfigParser;
import com.team4u.base.config.StringConfigParser;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Objects;

/**
 * 动态实例缓存提供者
 * <p>
 * 适用于任何根据配置创建对象并需要缓存的场景。
 * 支持两种模式：
 * 1. 标识模式：通过 configId 关联实例，支持根据输入内容的哈希值自动检测变更并重建实例。
 * 2. 匿名模式：直接以原始输入对象作为缓存键，彻底消除哈希冲突风险。
 *
 * @param <I> 输入源类型 (Input)
 * @param <C> 配置类型 (Config)
 * @param <T> 实例类型 (Type)
 * @author team4u
 */
public class DynamicInstanceProvider<I, C, T> {

    /**
     * 实例持有者缓存
     * Key: Object (可以是 configId 或 原始输入对象)
     * Value: InstanceHolder (包含配置快照 and 实例)
     */
    private final Cache<Object, InstanceHolder<C, T>> cache;

    /**
     * 输入源哈希缓存
     * 仅用于“标识模式”，记录 configId 对应的 input 的哈希值，用于变更检测。
     * Key: Object (configId)
     * Value: input 的 MurmurHash64 值
     */
    private final Cache<Object, Long> inputHashCache;

    /**
     * 配置解析器 (Input -> Config)
     */
    private final ConfigParser<I, C> configParser;

    /**
     * 实例工厂 (ID + Config -> Instance)
     */
    private final InstanceFactory<C, T> instanceFactory;

    /**
     * 分段锁桶，避免 String.intern() 的全局竞争和潜在内存问题
     */
    private final Object[] locks = new Object[128];

    /**
     * 构造函数
     *
     * @param cache           缓存实现
     * @param configParser    配置解析逻辑
     * @param instanceFactory 实例创建逻辑
     */
    public DynamicInstanceProvider(Cache<Object, InstanceHolder<C, T>> cache,
                                   ConfigParser<I, C> configParser,
                                   InstanceFactory<C, T> instanceFactory) {
        this.cache = cache;
        this.configParser = configParser;
        this.instanceFactory = instanceFactory;
        this.inputHashCache = CacheUtil.newLRUCache(cache.capacity());
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new Object();
        }
    }

    /**
     * 创建默认 LRU 缓存的提供者
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static <I, C, T> DynamicInstanceProvider<I, C, T> createLru(int capacity,
                                                                      ConfigParser<I, C> configParser,
                                                                      InstanceFactory<C, T> instanceFactory) {
        return new DynamicInstanceProvider(
                CacheUtil.newLRUCache(capacity),
                configParser,
                instanceFactory);
    }

    /**
     * 创建基于 String 输入源的 LRU 提供者
     */
    public static <C, T> DynamicInstanceProvider<String, C, T> createStringLru(int capacity,
                                                                              StringConfigParser<C> configParser,
                                                                              InstanceFactory<C, T> instanceFactory) {
        return createLru(capacity, configParser, instanceFactory);
    }

    /**
     * 获取或创建实例（标识模式）
     * <p>
     * 使用 configId 作为缓存键，并通过 input 的哈希值检测内容是否发生变更。
     */
    public T get(String configId, I input) {
        Assert.notBlank(configId, "configId must not be blank");
        return getInternal(configId, input, computeHash(input));
    }

    /**
     * 简化获取方法（匿名模式）
     * <p>
     * 直接以 input 对象作为缓存键，类似于 Cache 的加强版，不存在哈希冲突问题。
     */
    public T get(I input) {
        if (input == null) {
            return null;
        }
        return getInternal(input, input, null);
    }

    /**
     * 内部统一获取逻辑
     *
     * @param key       缓存键
     * @param input     输入对象
     * @param inputHash 输入对象的哈希（null 表示不进行哈希校验，直接使用 key 进行对象匹配）
     */
    private T getInternal(Object key, I input, Long inputHash) {
        // 1. 无锁快路径：检查缓存及哈希一致性（如果提供了哈希）
        InstanceHolder<C, T> holder = cache.get(key);
        if (holder != null && (inputHash == null || Objects.equals(inputHashCache.get(key), inputHash))) {
            return holder.instance;
        }

        // 2. 加锁慢路径
        synchronized (getLock(key)) {
            // 双重检查
            holder = cache.get(key);
            if (holder != null && (inputHash == null || Objects.equals(inputHashCache.get(key), inputHash))) {
                return holder.instance;
            }

            // 解析配置
            C newConfig = parseConfig(input);
            if (newConfig == null) {
                return null;
            }

            // 如果是标识模式且配置内容未变（仅 input 哈希变了，但解析出的 config 没变），则仅更新哈希并返回原实例
            if (inputHash != null && holder != null && Objects.equals(holder.config, newConfig)) {
                inputHashCache.put(key, inputHash);
                return holder.instance;
            }

            // 创建新实例，configId 采用 key 的字符串表示作为标识
            T newInstance = instanceFactory.create(generateConfigId(key), newConfig);
            cache.put(key, new InstanceHolder<>(newConfig, newInstance));

            if (inputHash != null) {
                inputHashCache.put(key, inputHash);
            }
            return newInstance;
        }
    }

    /**
     * 直接使用配置对象获取实例
     */
    public T getByConfig(String configId, C config) {
        Assert.notBlank(configId, "configId must not be blank");
        return getByConfigInternal(configId, config);
    }

    /**
     * 简化通过配置获取方法
     */
    public T getByConfig(C config) {
        if (config == null) {
            return null;
        }
        // 直接使用配置对象本身作为键，避免哈希冲突
        return getByConfigInternal(config, config);
    }

    /**
     * 内部统一直接通过配置获取实例
     */
    private T getByConfigInternal(Object key, C config) {
        InstanceHolder<C, T> holder = cache.get(key);

        if (holder != null && Objects.equals(holder.config, config)) {
            return holder.instance;
        }

        synchronized (getLock(key)) {
            holder = cache.get(key);
            if (holder != null && Objects.equals(holder.config, config)) {
                return holder.instance;
            }

            T newInstance = instanceFactory.create(generateConfigId(key), config);
            cache.put(key, new InstanceHolder<>(config, newInstance));
            inputHashCache.remove(key);
            return newInstance;
        }
    }

    /**
     * 生成实例的配置标识
     */
    private String generateConfigId(Object key) {
        if (key instanceof String) {
            return (String) key;
        }
        return String.valueOf(computeHash(key));
    }

    /**
     * 获取分段锁
     */
    private Object getLock(Object key) {
        return locks[(key.hashCode() & 0x7FFFFFFF) % locks.length];
    }

    /**
     * 计算对象的 64 位哈希值
     * 采用 MurmurHash3 算法，具有极低的冲突率和极高的执行性能
     */
    private long computeHash(Object obj) {
        if (obj == null) {
            return 0L;
        }

        if (obj instanceof String) {
            return HashUtil.murmur64(((String) obj).getBytes());
        }

        if (obj instanceof byte[]) {
            return HashUtil.murmur64((byte[]) obj);
        }

        return obj.hashCode();
    }

    private C parseConfig(I input) {
        if (input == null) {
            return null;
        }

        if (input instanceof String && StrUtil.isBlank((String) input)) {
            return null;
        }

        return configParser.parse(input);
    }

    public void invalidate(Object key) {
        cache.remove(key);
        inputHashCache.remove(key);
    }

    public void clear() {
        cache.clear();
        inputHashCache.clear();
    }

    public int size() {
        return cache.size();
    }

    @Data
    @AllArgsConstructor
    public static class InstanceHolder<C, T> {
        private final C config;
        private final T instance;
    }
}
