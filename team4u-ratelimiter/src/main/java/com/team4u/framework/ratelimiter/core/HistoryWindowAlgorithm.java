package com.team4u.framework.ratelimiter.core;

import com.team4u.framework.kv.KvStore;
import com.team4u.framework.ratelimiter.api.RateLimitReason;
import com.team4u.framework.ratelimiter.api.RateLimitResult;
import com.team4u.framework.ratelimiter.config.RateLimitRule;

import java.util.List;

/**
 * 历史窗口算法（固定窗口语义，服务端零存储）
 * <p>
 * <b>使用场景</b>：状态由调用方（如 APP 客户端）携带——客户端在本地记录请求历史
 * 时间戳列表，随请求上下文提交；服务端不落任何状态，仅依据历史数据裁决。
 * 结果中的 {@code decisionTimeMillis} 供客户端回填记录，保证双方时钟基准一致。
 * </p>
 * <p>
 * 窗口为 <b>epoch 对齐的固定窗口</b>：{@code windowStart = (now / windowMillis) * windowMillis}，
 * 单遍统计历史中 {@code ts >= windowStart} 的条目（未来时间戳也计入当前窗口——
 * 客户端时钟超前的记录不放大额度，统一计入当前窗口消耗）。裁决：
 * {@code count + permits <= threshold} 放行；跨过窗口边界（now 进入下一个
 * windowStart 周期）后计数自然归零。{@code retryAfter} 恒为当前窗口剩余时间。
 * </p>
 * <p>
 * 历史时间戳经 {@code rule.historyPath} 点路径从上下文提取（Map 取值/Bean 公有
 * getter/List 按下标导航，元素 Number/Date 转 epoch 毫秒），路径缺失或为 null
 * 视为空历史。注意：客户端携带历史天然可伪造，仅适合客户端自我节流，
 * 不能作为服务端防刷手段。
 * </p>
 *
 * @author jay.wu
 */
public class HistoryWindowAlgorithm implements RateLimitAlgorithm {

    /**
     * 算法名（规则 algorithm 字段取值）
     */
    public static final String KEY = "history-window";

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public Class<?>[] requiredCapabilities() {
        // 无状态算法：历史由调用方携带，服务端零存储，引擎不解析存储
        return new Class<?>[0];
    }

    @Override
    public RateLimitResult tryAcquire(RateLimitRule rule, KvStore store, String key,
                                      Object context, long nowMillis, int permits) {
        long windowMillis = rule.getWindowMillis();
        long threshold = rule.getThreshold();
        long windowStart = (nowMillis / windowMillis) * windowMillis;

        List<Long> history = HistoryPaths.extractTimestamps(context, rule.getHistoryPath());
        long count = 0;
        for (long ts : history) {
            if (ts >= windowStart) {
                count++;
            }
        }

        boolean allowed = count + permits <= threshold;
        return RateLimitResult.builder()
                .allowed(allowed)
                .ruleId(rule.getId())
                .remaining(Math.max(0, threshold - count))
                .retryAfterMillis(windowStart + windowMillis - nowMillis)
                .decisionTimeMillis(nowMillis)
                .reason(allowed ? RateLimitReason.PASS : RateLimitReason.THRESHOLD)
                .build();
    }
}
