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
 * 适用于任何根据配置创建对象并需要缓存的场景
 *
 * @param <I> 输入源类型 (Input)
 * @param <C> 配置类型 (Config)
 * @param <T> 实例类型 (Type)
 * @author team4u
 */
public class DynamicInstanceProvider<I, C, T> {

    /**
     * 实例持有者缓存
     * Key: configId
     * Value: InstanceHolder (包含配置快照 and 实例)
     */
    private final Cache<String, InstanceHolder<C, T>> cache;

    /**
     * 输入源哈希缓存 (64位哈希以减少冲突)
     * Key: configId
     * Value: input 的 MurmurHash64 值
     */
    private final Cache<String, Long> inputHashCache;

    /**
     * 配置解析器 (Input -> Config)
     */
    private final ConfigParser<I, C> configParser;

    /**
     * 实例工厂 (ID + Config -> Instance)
     */
    private final InstanceFactory<C, T> instanceFactory;

    /**
     * 构造函数
     *
     * @param cache           缓存实现
     * @param configParser    配置解析逻辑
     * @param instanceFactory 实例创建逻辑
     */
    public DynamicInstanceProvider(Cache<String, InstanceHolder<C, T>> cache,
                                   ConfigParser<I, C> configParser,
                                   InstanceFactory<C, T> instanceFactory) {
        this.cache = cache;
        this.configParser = configParser;
        this.instanceFactory = instanceFactory;
        this.inputHashCache = CacheUtil.newLRUCache(cache.capacity());
    }

    /**
     * 创建默认 LRU 缓存的提供者
     */
    public static <I, C, T> DynamicInstanceProvider<I, C, T> createLru(int capacity,
                                                                      ConfigParser<I, C> configParser,
                                                                      InstanceFactory<C, T> instanceFactory) {
        return new DynamicInstanceProvider<>(
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
        return new DynamicInstanceProvider<>(
                CacheUtil.newLRUCache(capacity),
                configParser,
                instanceFactory);
    }

    /**
     * 获取或创建实例
     */
    public T get(String configId, I input) {
        Assert.notBlank(configId, "configId must not be blank");

        long inputHash = computeHash(input);
        return computeSafely(configId, input, inputHash);
    }

    /**
     * 简化获取方法 (自动生成虚拟标识)
     */
    public T get(I input) {
        if (input == null) {
            return null;
        }
        return get(String.valueOf(computeHash(input)), input);
    }

    /**
     * 直接使用配置对象获取实例
     */
    public T getByConfig(String configId, C config) {
        Assert.notBlank(configId, "configId must not be blank");

        InstanceHolder<C, T> holder = cache.get(configId);

        if (holder != null && Objects.equals(holder.config, config)) {
            return holder.instance;
        }

        synchronized (configId.intern()) {
            holder = cache.get(configId);
            if (holder != null && Objects.equals(holder.config, config)) {
                return holder.instance;
            }

            T newInstance = instanceFactory.create(configId, config);
            cache.put(configId, new InstanceHolder<>(config, newInstance));
            inputHashCache.remove(configId);
            return newInstance;
        }
    }

    /**
     * 简化通过配置获取方法
     */
    public T getByConfig(C config) {
        if (config == null) {
            return null;
        }
        return getByConfig(String.valueOf(computeHash(config)), config);
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

        // 其它类型回退到 toString 哈希，或直接使用其 hashCode
        // 考虑到性能，这里优先使用 hashCode，虽然它是 32 位的
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

    private T computeSafely(String configId, I input, long inputHash) {
        synchronized (configId.intern()) {
            Long cachedHash = inputHashCache.get(configId);
            if (cachedHash != null && cachedHash == inputHash) {
                InstanceHolder<C, T> holder = cache.get(configId);
                if (holder != null) {
                    return holder.instance;
                }
            }

            C newConfig = parseConfig(input);
            if (newConfig == null) {
                return null;
            }

            InstanceHolder<C, T> holder = cache.get(configId);
            if (holder != null && Objects.equals(holder.config, newConfig)) {
                inputHashCache.put(configId, inputHash);
                return holder.instance;
            }

            T newInstance = instanceFactory.create(configId, newConfig);

            cache.put(configId, new InstanceHolder<>(newConfig, newInstance));
            inputHashCache.put(configId, inputHash);

            return newInstance;
        }
    }

    public void invalidate(String configId) {
        cache.remove(configId);
        inputHashCache.remove(configId);
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
