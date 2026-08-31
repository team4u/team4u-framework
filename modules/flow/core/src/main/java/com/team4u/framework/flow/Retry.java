package com.team4u.framework.flow;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.time.Duration;
import java.util.Objects;

/**
 * 流程步骤执行的不可变重试策略配置模型。
 *
 * <p>定义了最大尝试次数以及重试间隔（退避时长）：
 * <ul>
 *   <li>{@code maxAttempts}：最大尝试总次数（必须为正整数，包含第 1 次初始尝试。例如值为 3 表示最多 1 次初试 + 2 次重试）；</li>
 *   <li>{@code backoff}：重试之间的等待退避时长（必须非负，为 {@link Duration#ZERO} 时表示立即重试）。</li>
 * </ul>
 * </p>
 *
 * @author jay.wu
 */
@Getter
@Accessors(fluent = true)
@EqualsAndHashCode
@ToString
public final class Retry {
    /** 最大尝试次数（包含初试）。 */
    private final int maxAttempts;
    /** 重试间隔退避时长。 */
    private final Duration backoff;

    /**
     * 构造不可变重试策略。
     *
     * @param maxAttempts 最大尝试次数，必须 >= 1
     * @param backoff     退避间隔时长，不能为 null 且不能为负数
     * @throws NullPointerException     当 {@code backoff} 为 null 时抛出
     * @throws IllegalArgumentException 当 {@code maxAttempts < 1} 或 {@code backoff} 为负数时抛出
     */
    public Retry(int maxAttempts, Duration backoff) {
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be positive");
        this.maxAttempts = maxAttempts;
        this.backoff = Objects.requireNonNull(backoff, "backoff must not be null");
        if (backoff.isNegative()) throw new IllegalArgumentException("backoff must not be negative");
    }

    /**
     * 创建无退避间隔（立即重试）的重试配置。
     *
     * @param maxAttempts 最大尝试次数（包含初试），必须 >= 1
     * @return 初始化的 {@link Retry} 实例
     * @throws IllegalArgumentException 当 {@code maxAttempts < 1} 时抛出
     */
    public static Retry maxAttempts(int maxAttempts) {
        return new Retry(maxAttempts, Duration.ZERO);
    }

    /**
     * 基于当前最大尝试次数，派生指定退避间隔的新重试配置。
     *
     * @param backoff 新的退避时长，不能为 null 且不能为负数
     * @return 派生的 {@link Retry} 实例
     * @throws NullPointerException     当 {@code backoff} 为 null 时抛出
     * @throws IllegalArgumentException 当 {@code backoff} 为负数时抛出
     */
    public Retry withBackoff(Duration backoff) {
        return new Retry(maxAttempts, backoff);
    }
}

