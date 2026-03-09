package com.team4u.framework.retry.policy;

import com.team4u.framework.criterion.Criteria;
import com.team4u.framework.criterion.MatchContext;
import com.team4u.framework.retry.RetryExceptionUtil;
import com.team4u.framework.retry.backoff.Backoff;
import com.team4u.framework.retry.backoff.Backoffs;
import lombok.Getter;
import lombok.Singular;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 重试策略配置。
 *
 * <p>Builder 由 Lombok 自动生成，默认值解析和参数校验统一放在构造函数中，
 * 避免通过不同构建路径绕过约束。
 */
@Getter
public class RetryPolicy {
    /**
     * 最大尝试次数，包含首次执行，`-1` 表示无限重试。
     */
    private final int maxAttempts;

    /**
     * 本地进程内的最大尝试次数，主要用于接入持久化后端时控制前台重试配额。
     */
    private final Integer localAttempts;

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
     * @param maxAttempts       最大尝试次数，允许为 {@code null}，此时默认取 3
     * @param localAttempts     当前进程内的最大尝试次数，允许为 {@code null}
     * @param backoff           重试退避策略，允许为 {@code null}，此时默认使用固定 1000ms
     * @param retryOnExceptions 允许触发重试的异常类型集合
     * @param abortOnExceptions 命中后立即终止重试的异常类型集合
     * @param condition         额外的表达式条件，允许为 {@code null}，表达式语法可参考 {@link Criteria}
     */
    @lombok.Builder(builderClassName = "Builder")
    private RetryPolicy(
            Integer maxAttempts,
            Integer localAttempts,
            Backoff backoff,
            @Singular("retryOn") Set<Class<? extends Throwable>> retryOnExceptions,
            @Singular("abortOn") Set<Class<? extends Throwable>> abortOnExceptions,
            String condition) {
        // Builder 输入保持可空，便于在这里集中处理默认值。
        int resolvedMaxAttempts = maxAttempts == null ? 3 : maxAttempts;
        Backoff resolvedBackoff = backoff == null ? Backoffs.fixed(1000) : backoff;

        if (resolvedMaxAttempts == 0 || resolvedMaxAttempts < -1) {
            throw new IllegalArgumentException("maxAttempts must be greater than 0 or -1 (infinite retries)");
        }
        if (localAttempts != null && localAttempts <= 0) {
            throw new IllegalArgumentException("localAttempts must be greater than 0");
        }
        if (resolvedMaxAttempts != -1 && localAttempts != null && localAttempts > resolvedMaxAttempts) {
            throw new IllegalArgumentException("localAttempts must not be greater than maxAttempts");
        }

        this.maxAttempts = resolvedMaxAttempts;
        this.localAttempts = localAttempts;
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
            RetryContext contextData = new RetryContext(executedAttempts, getMaxAttempts(), cause);
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
        private final int attempt;
        private final int maxAttempts;
        private final Throwable cause;
        private final String message;

        public RetryContext(int attempt, int maxAttempts, Throwable cause) {
            this.attempt = attempt;
            this.maxAttempts = maxAttempts;
            this.cause = cause;
            this.message = cause != null && cause.getMessage() != null ? cause.getMessage() : "";
        }
    }
}
