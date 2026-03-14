package com.team4u.framework.base.instance;

import com.team4u.framework.base.cache.Cache;
import com.team4u.framework.base.cache.CacheUtil;
import com.team4u.framework.base.config.ConfigParser;
import com.team4u.framework.base.config.StringConfigParser;
import com.team4u.framework.base.util.StringUtil;

/**
 * 动态实例缓存提供者
 * <p>
 * 将输入源 (Input) 与配置对象 (Config) 分别映射到最终实例 (Instance)。
 * <p>
 * 核心特性：
 * 1. <b>输入/配置双缓存</b>：Input 与 Config 分别维护独立缓存，避免键空间污染。
 * 2. <b>分段锁并发控制</b>：在高并发 Cache Miss 时减少不必要的全局锁竞争。
 * 3. <b>双重获取支持</b>：支持通过原始 Input 获取，也支持通过已解析的 Config 直接获取。
 *
 * @param <I> 输入源类型 (Input)，必须正确实现 hashCode/equals
 * @param <C> 配置类型 (Config)，由 Input 解析而来
 * @param <T> 实例类型 (Type)，最终产物
 * @author jay.wu
 */
public class DynamicInstanceProvider<I, C, T> {

    /**
     * 输入缓存
     */
    private final Cache<I, T> inputCache;

    /**
     * 配置缓存
     */
    private final Cache<C, T> configCache;

    /**
     * 配置解析器 (Input -> Config)
     */
    private final ConfigParser<I, C> configParser;

    /**
     * 实例工厂 (Config -> Instance)
     */
    private final InstanceFactory<C, T> instanceFactory;

    /**
     * 输入分段锁桶
     */
    private final Object[] inputLocks = new Object[128];

    /**
     * 配置分段锁桶
     */
    private final Object[] configLocks = new Object[128];

    public DynamicInstanceProvider(Cache<I, T> inputCache,
                                   Cache<C, T> configCache,
                                   ConfigParser<I, C> configParser,
                                   InstanceFactory<C, T> instanceFactory) {
        this.inputCache = inputCache;
        this.configCache = configCache;
        this.configParser = configParser;
        this.instanceFactory = instanceFactory;
        for (int i = 0; i < inputLocks.length; i++) {
            inputLocks[i] = new Object();
            configLocks[i] = new Object();
        }
    }

    // ---------------- Factory Methods ----------------

    public static <I, C, T> DynamicInstanceProvider<I, C, T> createLru(int capacity,
                                                                       ConfigParser<I, C> configParser,
                                                                       InstanceFactory<C, T> instanceFactory) {
        return new DynamicInstanceProvider<>(
                CacheUtil.newLRUCache(capacity),
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
     * 流程：Input -> inputCache -> Parse Config -> Create Instance
     *
     * @param input 输入源
     * @return 实例，如果 input 为 null 或解析失败则返回 null
     */
    public T get(I input) {
        if (input == null) {
            return null;
        }

        T instance = inputCache.get(input);
        if (instance != null) {
            return instance;
        }

        synchronized (getInputLock(input)) {
            instance = inputCache.get(input);
            if (instance != null) {
                return instance;
            }

            if (input instanceof String && StringUtil.isBlank((String) input)) {
                return null;
            }
            C config = configParser.parse(input);
            if (config == null) {
                return null;
            }

            instance = instanceFactory.create(config);

            if (instance != null) {
                inputCache.put(input, instance);
            }
            return instance;
        }
    }

    /**
     * 直接根据配置对象获取实例
     * <p>
     * 适用于已经持有 Config 对象的场景，结果会缓存在 configCache 中。
     *
     * @param config 配置对象
     * @return 实例
     */
    public T getByConfig(C config) {
        if (config == null) {
            return null;
        }

        T instance = configCache.get(config);
        if (instance != null) {
            return instance;
        }

        synchronized (getConfigLock(config)) {
            instance = configCache.get(config);
            if (instance != null) {
                return instance;
            }

            instance = instanceFactory.create(config);

            if (instance != null) {
                configCache.put(config, instance);
            }
            return instance;
        }
    }

    /**
     * 移除输入缓存
     */
    public void invalidateInput(I input) {
        inputCache.remove(input);
    }

    /**
     * 移除配置缓存
     */
    public void invalidateConfig(C config) {
        configCache.remove(config);
    }

    public void clear() {
        inputCache.clear();
        configCache.clear();
    }

    /**
     * 输入缓存大小
     */
    public int inputCacheSize() {
        return inputCache.size();
    }

    /**
     * 配置缓存大小
     */
    public int configCacheSize() {
        return configCache.size();
    }

    // ---------------- Internal ----------------

    private Object getInputLock(I key) {
        return inputLocks[(key.hashCode() & 0x7FFFFFFF) % inputLocks.length];
    }

    private Object getConfigLock(C key) {
        return configLocks[(key.hashCode() & 0x7FFFFFFF) % configLocks.length];
    }
}
