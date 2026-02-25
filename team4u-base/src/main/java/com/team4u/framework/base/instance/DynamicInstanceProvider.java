package com.team4u.framework.base.instance;

import cn.hutool.cache.Cache;
import cn.hutool.cache.CacheUtil;
import cn.hutool.core.util.StrUtil;
import com.team4u.framework.base.config.ConfigParser;
import com.team4u.framework.base.config.StringConfigParser;

/**
 * 动态实例缓存提供者 (纯粹增强型)
 * <p>
 * 将输入源 (Input) 直接映射为最终实例 (Instance)。
 * <p>
 * 核心特性：
 * 1. <b>纯粹对象键</b>：直接利用 Input/Config 对象的 {@code equals/hashCode} 作为唯一标识。
 * 只要输入内容一致，即可复用缓存实例。
 * 2. <b>分段锁并发控制</b>：保留了 Striped Locking 机制，在高并发写入（Cache Miss）时避免全局锁竞争。
 * 3. <b>双重获取支持</b>：支持通过原始 Input 获取，也支持通过已解析的 Config 直接获取。
 *
 * @param <I> 输入源类型 (Input)，必须正确实现 hashCode/equals
 * @param <C> 配置类型 (Config)，由 Input 解析而来
 * @param <T> 实例类型 (Type)，最终产物
 * @author team4u
 */
public class DynamicInstanceProvider<I, C, T> {

    /**
     * 实例缓存
     * Key: I (输入对象) 或 C (配置对象)
     * Value: T (最终实例)
     */
    private final Cache<Object, T> cache;

    /**
     * 配置解析器 (Input -> Config)
     */
    private final ConfigParser<I, C> configParser;

    /**
     * 实例工厂 (Config -> Instance)
     */
    private final InstanceFactory<C, T> instanceFactory;

    /**
     * 分段锁桶 (Striped Locks)
     * 用于细粒度控制并发创建实例的过程
     */
    private final Object[] locks = new Object[128];

    public DynamicInstanceProvider(Cache<Object, T> cache,
                                   ConfigParser<I, C> configParser,
                                   InstanceFactory<C, T> instanceFactory) {
        this.cache = cache;
        this.configParser = configParser;
        this.instanceFactory = instanceFactory;
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new Object();
        }
    }

    // ---------------- Factory Methods ----------------

    public static <I, C, T> DynamicInstanceProvider<I, C, T> createLru(int capacity,
                                                                       ConfigParser<I, C> configParser,
                                                                       InstanceFactory<C, T> instanceFactory) {
        return new DynamicInstanceProvider<>(
                CacheUtil.newLRUCache(capacity),
                configParser,
                instanceFactory);
    }

    public static <C, T> DynamicInstanceProvider<String, C, T> createStringLru(int capacity,
                                                                               StringConfigParser<C> configParser,
                                                                               InstanceFactory<C, T> instanceFactory) {
        return createLru(capacity, configParser, instanceFactory);
    }

    // ---------------- Public API ----------------

    /**
     * 根据输入源获取实例
     * <p>
     * 流程：Input -> (Cache Miss) -> Parse Config -> Create Instance
     *
     * @param input 输入源 (作为缓存 Key)
     * @return 实例，如果 input 为 null 或解析失败则返回 null
     */
    public T get(I input) {
        if (input == null) {
            return null;
        }

        // 1. Fast-path: 查缓存
        T instance = cache.get(input);
        if (instance != null) {
            return instance;
        }

        // 2. Slow-path: 加锁创建
        synchronized (getLock(input)) {
            // DCL (Double Check Lock)
            instance = cache.get(input);
            if (instance != null) {
                return instance;
            }

            // 解析配置
            if (input instanceof String && StrUtil.isBlank((String) input)) {
                return null;
            }
            C config = configParser.parse(input);
            if (config == null) {
                return null;
            }

            // 创建实例
            instance = instanceFactory.create(config);
            
            if (instance != null) {
                cache.put(input, instance);
            }
            return instance;
        }
    }

    /**
     * 直接根据配置对象获取实例
     * <p>
     * 适用于已经持有 Config 对象的场景。
     *
     * @param config 配置对象 (作为缓存 Key)
     * @return 实例
     */
    public T getByConfig(C config) {
        if (config == null) {
            return null;
        }

        T instance = cache.get(config);
        if (instance != null) {
            return instance;
        }

        synchronized (getLock(config)) {
            instance = cache.get(config);
            if (instance != null) {
                return instance;
            }

            instance = instanceFactory.create(config);
            
            if (instance != null) {
                cache.put(config, instance);
            }
            return instance;
        }
    }

    /**
     * 移除缓存
     */
    public void invalidate(Object key) {
        cache.remove(key);
    }

    public void clear() {
        cache.clear();
    }

    public int size() {
        return cache.size();
    }

    // ---------------- Internal ----------------

    private Object getLock(Object key) {
        return locks[(key.hashCode() & 0x7FFFFFFF) % locks.length];
    }
}
