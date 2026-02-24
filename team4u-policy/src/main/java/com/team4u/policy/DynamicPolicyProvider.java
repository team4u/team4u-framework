package com.team4u.policy;

import cn.hutool.cache.Cache;
import cn.hutool.cache.CacheUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.StrUtil;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Objects;

/**
 * 动态策略缓存提供者 (通用输入源版)
 *
 * @param <I> 输入源类型 (Input)
 * @param <C> 配置类型 (Config)
 * @param <P> 策略类型 (Policy)
 * @author team4u
 */
public class DynamicPolicyProvider<I, C, P> {

    /**
     * 策略持有者缓存
     * Key: configId
     * Value: PolicyHolder (包含配置快照 and 策略实例)
     */
    private final Cache<String, PolicyHolder<C, P>> cache;

    /**
     * 输入源哈希缓存
     * Key: configId
     * Value: input 的哈希值，用于快速比对输入是否变化
     * 使用与 cache 相同容量的 LRU 缓存，确保与主缓存同步淘汰
     */
    private final Cache<String, Integer> inputHashCache;

    /**
     * 配置解析器 (Input -> Config)
     */
    private final ConfigParser<I, C> configParser;

    /**
     * 策略工厂 (ID + Config -> Policy)
     */
    private final PolicyFactory<C, P> policyFactory;

    /**
     * 构造函数
     *
     * @param cache         缓存实现 (如 CacheUtil.newLRUCache(100))
     * @param configParser  配置解析逻辑
     * @param policyFactory 策略创建逻辑
     */
    public DynamicPolicyProvider(Cache<String, PolicyHolder<C, P>> cache,
            ConfigParser<I, C> configParser,
            PolicyFactory<C, P> policyFactory) {
        this.cache = cache;
        this.configParser = configParser;
        this.policyFactory = policyFactory;
        // 创建与主缓存同容量的 LRU 缓存，确保同步淘汰
        this.inputHashCache = CacheUtil.newLRUCache(cache.capacity());
    }

    /**
     * 创建默认 LRU 缓存的提供者
     */
    public static <I, C, P> DynamicPolicyProvider<I, C, P> createLru(int capacity,
            ConfigParser<I, C> configParser,
            PolicyFactory<C, P> policyFactory) {
        return new DynamicPolicyProvider<>(
                CacheUtil.newLRUCache(capacity),
                configParser,
                policyFactory);
    }

    /**
     * 创建基于 String 输入源的 LRU 提供者 (便捷工厂方法)
     */
    public static <C, P> DynamicPolicyProvider<String, C, P> createStringLru(int capacity,
            StringConfigParser<C> configParser,
            PolicyFactory<C, P> policyFactory) {
        return new DynamicPolicyProvider<>(
                CacheUtil.newLRUCache(capacity),
                configParser,
                policyFactory);
    }

    /**
     * 获取或创建策略
     * <p>
     * 逻辑：
     * 1. 计算 input 的哈希值（比 JSON 解析快几个数量级）
     * 2. 快速检查：哈希未变，直接返回缓存策略，跳过解析
     * 3. 哈希变了或首次访问，才执行解析
     * 4. 检查缓存中的 config 是否与新 config 相等
     * 5. 如果相等，直接返回缓存的 policy
     * 6. 如果不相等（配置变更）或不存在，则创建新策略并更新缓存
     *
     * @param configId 配置唯一标识
     * @param input    输入源 (String, File, Map...)
     * @return 策略实例
     */
    public P get(String configId, I input) {
        Assert.notBlank(configId, "configId must not be blank");

        // 1. 计算 input 的哈希值（比 JSON 解析快几个数量级）
        int inputHash = computeInputHash(input);

        // 3. 哈希变了或首次访问，执行带锁的计算逻辑
        return computeSafely(configId, input, inputHash);
    }

    /**
     * 直接使用配置对象获取策略
     */
    public P getByConfig(String configId, C config) {
        Assert.notBlank(configId, "configId must not be blank");

        PolicyHolder<C, P> holder = cache.get(configId);

        if (holder != null && Objects.equals(holder.config, config)) {
            return holder.policy;
        }

        // 直接通过配置获取时，绕过 inputHash 检查
        synchronized (configId.intern()) {
            holder = cache.get(configId);
            if (holder != null && Objects.equals(holder.config, config)) {
                return holder.policy;
            }

            P newPolicy = policyFactory.create(configId, config);
            cache.put(configId, new PolicyHolder<>(config, newPolicy));
            inputHashCache.remove(configId); // 配置已知但 input 未知，清理哈希防误判
            return newPolicy;
        }
    }

    /**
     * 计算输入源的哈希值
     * 用于快速比对输入是否变化，避免不必要的解析
     */
    private int computeInputHash(I input) {
        if (input == null) {
            return 0;
        }
        // 对于字符串类型，直接使用内容哈希
        if (input instanceof String) {
            return input.hashCode();
        }
        // 其他类型使用默认哈希
        return input.hashCode();
    }

    private C parseConfig(I input) {
        if (input == null) {
            return null;
        }

        // 针对字符串类型的特殊逻辑处理
        if (input instanceof String && StrUtil.isBlank((String) input)) {
            return null;
        }

        return configParser.parse(input);
    }

    /**
     * 线程安全地创建并更新缓存
     *
     * @param configId  配置ID
     * @param input     原始输入源
     * @param inputHash 输入源哈希值
     */
    private P computeSafely(String configId, I input, int inputHash) {
        // 使用 intern 锁或其它分段锁机制，避免全局锁，针对同一 configId 串行化
        synchronized (configId.intern()) {
            // 1. 双重检查：再次检查哈希缓存
            Integer cachedHash = inputHashCache.get(configId);
            if (cachedHash != null && cachedHash == inputHash) {
                PolicyHolder<C, P> holder = cache.get(configId);
                if (holder != null) {
                    return holder.policy;
                }
            }

            // 2. 只有在哈希不匹配或缓存丢失时，才执行耗时的解析
            C newConfig = parseConfig(input);
            if (newConfig == null) {
                return null;
            }

            // 3. 再次检查配置是否真的变更（防止不同 input 但解析后 config 相同的情况）
            PolicyHolder<C, P> holder = cache.get(configId);
            if (holder != null && Objects.equals(holder.config, newConfig)) {
                // 如果配置没变，虽然 input 可能变了（但解析结果一致），我们也更新哈希以便下次快速命中
                inputHashCache.put(configId, inputHash);
                return holder.policy;
            }

            // 4. 创建新策略
            P newPolicy = policyFactory.create(configId, newConfig);

            // 5. 更新缓存
            cache.put(configId, new PolicyHolder<>(newConfig, newPolicy));
            inputHashCache.put(configId, inputHash);

            return newPolicy;
        }
    }

    /**
     * 手动失效
     */
    public void invalidate(String configId) {
        cache.remove(configId);
        inputHashCache.remove(configId);
    }

    /**
     * 清空所有
     */
    public void clear() {
        cache.clear();
        inputHashCache.clear();
    }

    /**
     * 获取缓存状态信息
     */
    public int size() {
        return cache.size();
    }

    /**
     * 内部持有者对象
     */
    @Data
    @AllArgsConstructor
    public static class PolicyHolder<C, P> {
        private final C config;
        private final P policy;
    }
}
