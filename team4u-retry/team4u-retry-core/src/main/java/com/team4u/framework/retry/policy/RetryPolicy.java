package com.team4u.framework.retry.policy;

import com.team4u.framework.criterion.Criteria;
import com.team4u.framework.criterion.MatchContext;
import com.team4u.framework.retry.backoff.Backoff;
import com.team4u.framework.retry.backoff.Backoffs;
import com.team4u.framework.retry.util.RetryExceptionUtil;
import lombok.Getter;
import lombok.Singular;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 重试策略配置。
 *
 * <p>
 * Builder 由 Lombok 自动生成，默认值解析和参数校验统一放在构造函数中，
 * 避免通过不同构建路径绕过约束。
 */
@Getter
public class RetryPolicy {
    /**
     * 最大尝试次数，包含首次执行，`-1` 表示无限重试。
     */
    private final int maxAttempts;

    /**
     * 在托管模型下，前台同步执行的最大尝试次数。
     * INLINE 模式下不可配置该值，MANAGED 模式下必须显式配置。
     */
    private final Integer foregroundAttempts;

    /**
     * 每次重试之间的退避策略。
     */
    private final Backoff backoff;

    /**
     * 仅当异常命中该集合中的类型时才允许继续重试。
     */
    private final Set<Class<? extends Throwable>> retryOnExceptions;

    /**
     * 当异常命中该集合中的类型时立即终止重试。
     */
    private final Set<Class<? extends Throwable>> abortOnExceptions;

    /**
     * 额外的表达式条件，用于对是否重试做更细粒度控制。
     * 表达式语法可参考 {@link Criteria} 的相关实现。
     */
    private final String condition;

    /**
     * 构造重试策略实例。
     *
     * @param maxRetries            最大重试次数，允许为 {@code null}，此时默认取 2
     * @param foregroundMaxAttempts 前台进程内最大执行次数，允许为 {@code null}
     * @param backoff               重试退避策略，允许为 {@code null}，此时默认使用固定 1000ms
     * @param retryOnExceptions     允许触发重试的异常类型集合
     * @param abortOnExceptions     命中后立即终止重试的异常类型集合
     * @param condition             额外的表达式条件，允许为 {@code null}，表达式语法可参考 {@link Criteria}
     */
    @lombok.Builder(builderClassName = "Builder")
    private RetryPolicy(
            Integer maxRetries,
            Integer foregroundMaxAttempts,
            Backoff backoff,
            @Singular("retryOn") Set<Class<? extends Throwable>> retryOnExceptions,
            @Singular("abortOn") Set<Class<? extends Throwable>> abortOnExceptions,
            String condition) {
        // Builder 输入保持可空，便于在这里集中处理默认值。
        int resolvedMaxRetries = maxRetries == null ? 2 : maxRetries;
        int resolvedMaxAttempts = resolvedMaxRetries == -1 ? -1 : resolvedMaxRetries + 1;
        Backoff resolvedBackoff = backoff == null ? Backoffs.fixed(1000) : backoff;

        if (resolvedMaxRetries < -1) {
            throw new IllegalArgumentException("maxRetries must be greater than or equal to 0, or -1 (infinite retries)");
        }
        if (foregroundMaxAttempts != null && foregroundMaxAttempts <= 0) {
            throw new IllegalArgumentException("foregroundMaxAttempts must be greater than 0");
        }
        if (resolvedMaxAttempts != -1
                && foregroundMaxAttempts != null
                && foregroundMaxAttempts > resolvedMaxAttempts) {
            throw new IllegalArgumentException("foregroundMaxAttempts must not be greater than maxRetries + 1");
        }

        this.maxAttempts = resolvedMaxAttempts;
        this.foregroundAttempts = foregroundMaxAttempts;
        this.backoff = resolvedBackoff;
        // 在构造阶段完成防御性拷贝，避免后续 Builder 继续修改时影响已生成对象。
        this.retryOnExceptions = immutableCopy(retryOnExceptions);
        this.abortOnExceptions = immutableCopy(abortOnExceptions);
        this.condition = condition;
    }

    private static Set<Class<? extends Throwable>> immutableCopy(Set<Class<? extends Throwable>> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptySet();
        }
        // 先拷贝再包装，确保即使调用方复用原始集合，也不会破坏对象不可变性。
        return Collections.unmodifiableSet(new HashSet<>(source));
    }

    public boolean canRetry(int executedAttempts, Throwable ex) {
        if (maxAttempts != -1 && executedAttempts >= maxAttempts) {
            return false;
        }

        Throwable cause = extractCause(ex);

        if (!abortOnExceptions.isEmpty() && matches(cause, abortOnExceptions)) {
            return false;
        }

        if (!retryOnExceptions.isEmpty() && !matches(cause, retryOnExceptions)) {
            return false;
        }

        if (condition != null && !condition.isEmpty()) {
            RetryContext contextData = new RetryContext(executedAttempts - 1, getMaxAttempts(), cause);
            MatchContext ctx = MatchContext.of(contextData);
            return Criteria.global().matches(condition, ctx);
        }

        return true;
    }

    public long getDelayMillis(int currentAttempt) {
        return backoff.calculateMillis(currentAttempt);
    }

    private boolean matches(Throwable ex, Set<Class<? extends Throwable>> classes) {
        return classes.stream().anyMatch(clazz -> clazz.isAssignableFrom(ex.getClass()));
    }

    private Throwable extractCause(Throwable ex) {
        return RetryExceptionUtil.unwrap(ex);
    }

    @Getter
    public static class RetryContext {
        private final int retryCount;
        private final int maxRetries;
        private final Throwable cause;
        private final String message;

        public RetryContext(int retryCount, int maxAttempts, Throwable cause) {
            this.retryCount = retryCount;
            this.maxRetries = maxAttempts == -1 ? -1 : maxAttempts - 1;
            this.cause = cause;
            this.message = cause != null && cause.getMessage() != null ? cause.getMessage() : "";
        }
    }
}
