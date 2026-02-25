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
 * 动态实例缓存提供者 (增强型对象级缓存)
 * <p>
 * 适用于任何根据配置动态创建对象并需要缓存的高并发场景。
 * <p>
 * 核心特性：
 * 1. <b>双模式支持</b>：
 *    - <b>标识模式 (ID-Mode)</b>：使用明确的 {@code configId} 作为缓存键。底层自动计算 {@code input} 的 64位 MurmurHash，当输入内容变更时，能自动触发实例的重新解析与重建。
 *    - <b>匿名模式 (Anonymous-Mode)</b>：省略 {@code configId}，直接将 {@code input} 或 {@code config} 对象本身作为缓存键。通过对象的 {@code equals/hashCode} 进行精准匹配，从根源上消除哈希碰撞导致的数据串流风险。
 * 2. <b>高性能并发控制</b>：引入分段锁 (Striped Locking) 机制，替代传统的 {@code String.intern()} 全局锁，极大降低了并发锁竞争，并消除了动态字符串导致的常量池内存泄漏隐患。
 * 3. <b>快速路径优化</b>：在无变更的情况下，通过散列值比对或对象等值判断实现无锁快速返回 (Fast-Path)。
 *
 * @param <I> 输入源类型 (Input)，如 JSON 字符串、Map 等
 * @param <C> 配置类型 (Config)，由 Input 解析而来的强类型配置对象
 * @param <T> 实例类型 (Type)，由 Config 构建的最终实例
 * @author team4u
 */
public class DynamicInstanceProvider<I, C, T> {

    /**
     * 实例持有者缓存
     * Key: Object (标识模式下为 configId，匿名模式下为原始输入对象)
     * Value: InstanceHolder (包含配置快照与实例对象)
     */
    private final Cache<Object, InstanceHolder<C, T>> cache;

    /**
     * 输入源哈希缓存
     * <p>
     * 仅用于“标识模式”，记录 configId 对应的 input 的内容哈希值，用于快速检测配置是否发生变更。
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
     * 分段锁桶 (Striped Locks)
     * <p>
     * 用于对缓存 Key 进行细粒度锁定，避免 String.intern() 的全局竞争和潜在内存问题。
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
     * 使用 {@code configId} 作为缓存键。内部会计算 {@code input} 的 64位哈希值。
     * 若同一 {@code configId} 对应的输入哈希发生改变，会自动触发重新解析和实例重建。
     *
     * @param configId 配置的唯一标识 (不能为空)
     * @param input    输入源（用于解析配置）
     * @return 实例
     */
    public T get(String configId, I input) {
        Assert.notBlank(configId, "configId must not be blank");
        return getInternal(configId, input, computeHash(input));
    }

    /**
     * 简化获取方法（匿名模式）
     * <p>
     * 直接将输入源 {@code input} 对象本身作为缓存键。
     * 要求 {@code input} 所在类正确实现了 {@code equals} 和 {@code hashCode} 方法。
     * 此模式不存在哈希碰撞的风险，行为类似于加强版的本地 Cache。
     *
     * @param input 输入源
     * @return 实例
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
     * @param key       缓存键 (configId 或 原始输入对象)
     * @param input     输入对象
     * @param inputHash 输入对象的哈希（null 表示匿名模式，不进行哈希校验，直接使用 key 进行对象匹配）
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

            // 如果是标识模式且配置内容未变（仅 input 哈希变了，但解析出的 config 等值），则仅更新哈希并返回原实例
            if (inputHash != null && holder != null && Objects.equals(holder.config, newConfig)) {
                inputHashCache.put(key, inputHash);
                return holder.instance;
            }

            // 创建新实例
            T newInstance = instanceFactory.create(generateConfigId(key), newConfig);
            cache.put(key, new InstanceHolder<>(newConfig, newInstance));

            if (inputHash != null) {
                inputHashCache.put(key, inputHash);
            }
            return newInstance;
        }
    }

    /**
     * 直接使用配置对象获取实例（标识模式）
     * <p>
     * 使用 {@code configId} 作为缓存键。若发现缓存的配置与当前 {@code config} 不一致（基于 {@code equals}），则重建实例。
     *
     * @param configId 配置的唯一标识 (不能为空)
     * @param config   配置对象
     * @return 实例
     */
    public T getByConfig(String configId, C config) {
        Assert.notBlank(configId, "configId must not be blank");
        return getByConfigInternal(configId, config);
    }

    /**
     * 简化通过配置获取实例（匿名模式）
     * <p>
     * 直接将配置对象 {@code config} 本身作为缓存键。
     * 要求 {@code config} 所在类正确实现了 {@code equals} 和 {@code hashCode} 方法。
     *
     * @param config 配置对象
     * @return 实例
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
     * 生成供 InstanceFactory 使用的配置标识
     *
     * @param key 缓存键
     * @return 配置标识字符串
     */
    private String generateConfigId(Object key) {
        if (key instanceof String) {
            return (String) key;
        }
        // 匿名模式下，回退为计算对象的哈希值作为工厂所需的标识
        return String.valueOf(computeHash(key));
    }

    /**
     * 获取分段锁对象
     * <p>
     * 基于 Key 的 hashCode 将锁分散到 128 个桶中，极大降低并发写入时的锁竞争。
     */
    private Object getLock(Object key) {
        return locks[(key.hashCode() & 0x7FFFFFFF) % locks.length];
    }

    /**
     * 计算对象的 64 位哈希值
     * <p>
     * 采用 MurmurHash3 算法，主要用于标识模式下的内容变更快速检测。
     * 注意：对于普通的 {@code Object}，直接回退到其自带的 32位 {@code hashCode()}。
     *
     * @param obj 需要计算哈希的对象
     * @return 哈希值
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

    /**
     * 解析配置
     */
    private C parseConfig(I input) {
        if (input == null) {
            return null;
        }

        if (input instanceof String && StrUtil.isBlank((String) input)) {
            return null;
        }

        return configParser.parse(input);
    }

    /**
     * 手动失效缓存
     *
     * @param key 缓存键 (标识模式下为 configId，匿名模式下为 原始对象)
     */
    public void invalidate(Object key) {
        cache.remove(key);
        inputHashCache.remove(key);
    }

    /**
     * 清空所有缓存
     */
    public void clear() {
        cache.clear();
        inputHashCache.clear();
    }

    /**
     * 获取当前缓存的数量
     */
    public int size() {
        return cache.size();
    }

    /**
     * 实例持有者 (保存配置快照与实例的映射)
     */
    @Data
    @AllArgsConstructor
    public static class InstanceHolder<C, T> {
        private final C config;
        private final T instance;
    }
}
