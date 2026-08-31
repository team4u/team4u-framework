package com.team4u.framework.flow.ratelimiter;

import com.team4u.framework.flow.api.Gate;
import com.team4u.framework.flow.api.Policy;
import com.team4u.framework.flow.api.PolicyContext;
import com.team4u.framework.flow.model.Failure;
import com.team4u.framework.flow.model.Reason;
import com.team4u.framework.ratelimiter.api.RateLimitResult;
import com.team4u.framework.ratelimiter.api.RateLimiters;
import com.team4u.framework.ratelimiter.core.RateLimitEngine;
import lombok.Builder;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * 基于 team4u-ratelimiter 的通用流程限流治理策略
 * <p>
 * 通过将限流引擎裁决结果（{@link RateLimitResult}）转化为 Flow 门控决策（{@link Gate}），
 * 实现流程级别的细粒度限流防护。支持：
 * <ul>
 *   <li><b>故障模式（{@link RateLimitAction#FAIL}，默认）</b>：产生 Failed 结果，可触发外层 {@code retry} 退避重试；</li>
 *   <li><b>拒绝模式（{@link RateLimitAction#REJECT}）</b>：产生 Rejected 结果，直接业务短路降级，不触发重试；</li>
 *   <li><b>动态路由上下文与许可数提取</b>：支持通过函数提取限流上下文与动态消耗许可。</li>
 * </ul>
 * </p>
 *
 * <p><b>容错语义（fail-open）：</b>当未显式注入 {@code engine} 且全局 {@link RateLimiters} 尚未初始化时，
 * 首次调用会懒加载默认引擎（全局配置 + 内存存储）并输出一次 warn 日志——限流器不可用不阻断业务主链路。
 * 许可数在每次 {@code before} 入口校验：提取结果为负数时抛出带清晰信息的 {@link IllegalArgumentException}，
 * 由引擎转化为 {@code POLICY_EXCEPTION(Failed)}。</p>
 *
 * @param <K> 策略路由键（或步骤入参）类型
 * @author jay.wu
 */
@Getter
public class RateLimitPolicy<K> implements Policy<K> {

    private static final Logger LOG = LoggerFactory.getLogger(RateLimitPolicy.class);

    /** 是否已输出过懒加载默认引擎的 warn 日志（仅首次告警，避免日志风暴）。 */
    private static volatile boolean lazyEngineWarned;

    /**
     * 默认限流超限失败诊断码
     */
    public static final String DEFAULT_FAILURE_CODE = "RATE_LIMIT_EXCEEDED";

    /**
     * 默认限流业务拒绝诊断码
     */
    public static final String DEFAULT_REJECT_CODE = "RATE_LIMIT_REJECTED";

    private final String point;
    private final Function<K, ?> contextExtractor;
    private final Integer permits;
    private final Function<K, Integer> permitsExtractor;
    private final RateLimitAction action;
    private final RateLimitEngine engine;
    private final BiFunction<RateLimitResult, K, Failure> failureFactory;
    private final BiFunction<RateLimitResult, K, Reason> reasonFactory;

    @Builder(toBuilder = true)
    public RateLimitPolicy(
            String point,
            Function<K, ?> contextExtractor,
            Integer permits,
            Function<K, Integer> permitsExtractor,
            RateLimitAction action,
            RateLimitEngine engine,
            BiFunction<RateLimitResult, K, Failure> failureFactory,
            BiFunction<RateLimitResult, K, Reason> reasonFactory) {
        this.point = Objects.requireNonNull(point, "point must not be null");
        this.contextExtractor = contextExtractor;
        this.permits = permits;
        if (permits != null && permits < 0) {
            throw new IllegalArgumentException(
                    "permits must be >= 0 but got " + permits + " for point [" + point + "]");
        }
        if (permitsExtractor != null) {
            this.permitsExtractor = permitsExtractor;
        } else if (permits != null) {
            int p = permits;
            this.permitsExtractor = k -> p;
        } else {
            this.permitsExtractor = k -> 1;
        }
        this.action = action != null ? action : RateLimitAction.FAIL;
        this.engine = engine;
        this.failureFactory = failureFactory;
        this.reasonFactory = reasonFactory;
    }

    /**
     * 创建默认限流策略（默认 FAIL 模式，申请 1 个许可）
     *
     * @param point 限流检查点
     * @param <K>   键类型
     * @return 限流策略实例
     */
    public static <K> RateLimitPolicy<K> of(String point) {
        return RateLimitPolicy.<K>builder().point(point).build();
    }

    /**
     * 创建默认限流策略并指定上下文提取函数
     *
     * @param point            限流检查点
     * @param contextExtractor 上下文提取函数
     * @param <K>              键类型
     * @return 限流策略实例
     */
    public static <K> RateLimitPolicy<K> of(String point, Function<K, ?> contextExtractor) {
        return RateLimitPolicy.<K>builder()
                .point(point)
                .contextExtractor(contextExtractor)
                .build();
    }

    /**
     * 创建故障模式限流策略（触发重试）
     *
     * @param point 限流检查点
     * @param <K>   键类型
     * @return 限流策略实例
     */
    public static <K> RateLimitPolicy<K> fail(String point) {
        return RateLimitPolicy.<K>builder()
                .point(point)
                .action(RateLimitAction.FAIL)
                .build();
    }

    /**
     * 创建故障模式限流策略并指定上下文提取函数
     *
     * @param point            限流检查点
     * @param contextExtractor 上下文提取函数
     * @param <K>              键类型
     * @return 限流策略实例
     */
    public static <K> RateLimitPolicy<K> fail(String point, Function<K, ?> contextExtractor) {
        return RateLimitPolicy.<K>builder()
                .point(point)
                .contextExtractor(contextExtractor)
                .action(RateLimitAction.FAIL)
                .build();
    }

    /**
     * 创建业务拒绝模式限流策略（直接短路降级）
     *
     * @param point 限流检查点
     * @param <K>   键类型
     * @return 限流策略实例
     */
    public static <K> RateLimitPolicy<K> reject(String point) {
        return RateLimitPolicy.<K>builder()
                .point(point)
                .action(RateLimitAction.REJECT)
                .build();
    }

    /**
     * 创建业务拒绝模式限流策略并指定上下文提取函数
     *
     * @param point            限流检查点
     * @param contextExtractor 上下文提取函数
     * @param <K>              键类型
     * @return 限流策略实例
     */
    public static <K> RateLimitPolicy<K> reject(String point, Function<K, ?> contextExtractor) {
        return RateLimitPolicy.<K>builder()
                .point(point)
                .contextExtractor(contextExtractor)
                .action(RateLimitAction.REJECT)
                .build();
    }

    @Override
    public Gate before(PolicyContext context, K key) {
        Object rateLimitCtx = contextExtractor != null ? contextExtractor.apply(key) : key;
        int permits = permitsExtractor != null ? permitsExtractor.apply(key) : 1;
        if (permits < 0) {
            // 会成为 POLICY_EXCEPTION(Failed)，message 说明了根因（permitsExtractor 返回负值）
            throw new IllegalArgumentException(
                    "Rate limit permits must be >= 0 but got " + permits
                            + " for point [" + point + "] (check permitsExtractor/permits configuration)");
        }

        RateLimitResult result;
        if (engine != null) {
            result = engine.acquire(point, rateLimitCtx, permits);
        } else {
            if (!lazyEngineWarned) {
                lazyEngineWarned = true;
                LOG.warn("No RateLimitEngine injected for point [{}]; falling back to global "
                        + "RateLimiters engine (lazily created with global config + in-memory store "
                        + "when uninitialized) -- rate limiting is fail-open until initialized", point);
            }
            result = RateLimiters.acquire(point, rateLimitCtx, permits);
        }

        if (result.isAllowed()) {
            return Gate.proceed();
        }

        if (action == RateLimitAction.REJECT) {
            Reason reason = reasonFactory != null
                    ? reasonFactory.apply(result, key)
                    : defaultReason(result);
            return Gate.reject(reason);
        }

        Failure failure = failureFactory != null
                ? failureFactory.apply(result, key)
                : defaultFailure(result);
        return Gate.fail(failure);
    }

    /**
     * 构建默认失败诊断信息
     */
    protected Failure defaultFailure(RateLimitResult result) {
        StringBuilder msg = new StringBuilder("Rate limit exceeded for point [")
                .append(point).append("]");
        if (result.getRuleId() != null) {
            msg.append(", rule [").append(result.getRuleId()).append("]");
        }
        if (result.getRetryAfterMillis() != null) {
            msg.append(", retryAfter=").append(result.getRetryAfterMillis()).append("ms");
        }
        return Failure.of(DEFAULT_FAILURE_CODE, msg.toString());
    }

    /**
     * 构建默认业务拒绝原因
     */
    protected Reason defaultReason(RateLimitResult result) {
        StringBuilder msg = new StringBuilder("Rate limit rejected for point [")
                .append(point).append("]");
        if (result.getRuleId() != null) {
            msg.append(", rule [").append(result.getRuleId()).append("]");
        }
        return Reason.of(DEFAULT_REJECT_CODE, msg.toString());
    }
}
