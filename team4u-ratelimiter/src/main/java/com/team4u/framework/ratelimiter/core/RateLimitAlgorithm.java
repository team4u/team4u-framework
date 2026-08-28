package com.team4u.framework.ratelimiter.core;

import com.team4u.framework.kv.KvStore;
import com.team4u.framework.policy.api.KeyedPolicy;
import com.team4u.framework.ratelimiter.api.RateLimitResult;
import com.team4u.framework.ratelimiter.config.RateLimitRule;

/**
 * 限流算法规约（SPI）
 * <p>
 * 一个算法实现一个 {@link #key()} 命名的策略，引擎按规则中的 {@code algorithm}
 * 查表路由。算法应为无状态单例（纯决策逻辑，零存储代码），全部状态保存在
 * KvStore 中，存储能力经 {@code KvStores.capabilityOf} 协商获得。
 * </p>
 * <p>
 * {@link #requiredCapabilities()} 声明所需 kv 能力接口，引擎在规则加载期校验
 * 存储是否齐备；返回空数组表示无状态算法（如 history-window），引擎传
 * {@code null} 存储、不解析规则中的 store 配置。
 * </p>
 *
 * @author jay.wu
 */
public interface RateLimitAlgorithm extends KeyedPolicy<String> {

    /**
     * 声明所需 kv 能力接口（引擎在规则加载期逐一校验存储可提供）
     *
     * @return 能力接口数组；空数组 = 无状态算法，引擎不为其解析存储
     */
    Class<?>[] requiredCapabilities();

    /**
     * 执行一次限流裁决
     *
     * @param rule      限流规则（已通过加载期校验）
     * @param store     规则解析出的存储；无状态算法为 {@code null}
     * @param key       计数键（{@code ruleId + "." + 渲染后的键模板}），算法自行组装 SpaceKey
     * @param context   检查上下文（Map 或 Bean，history-window 据此提取历史时间戳）
     * @param nowMillis 裁决时刻（epoch 毫秒）
     * @param permits   本次申请的许可数（0 = 窥探：仅计数不占用）
     * @return 裁决结果（拒绝 reason=THRESHOLD；存储故障抛 {@code KvStoreException} 由引擎按 failOpen 处置）
     */
    RateLimitResult tryAcquire(RateLimitRule rule, KvStore store, String key,
                               Object context, long nowMillis, int permits);
}
