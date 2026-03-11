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

@Getter
public class RetryPolicy {
    /**
     * 最大重试次数（不包含首次执行），`-1` 表示无限重试。
     */
    private final int maxRetries;

    /**
     * 在托管模型下，前台同步执行的最大尝试次数。
     * INLINE 模式下不可配置该值，MANAGED 模式下必须显式配置。
     */
    private final Integer foregroundMaxRetries;

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
     * @param maxRetries           最大重试次数（不包含首次执行），允许为 {@code null}，此时默认取 2
     * @param foregroundMaxRetries 前台最大重试次数（不包含首次执行），允许为 {@code null}
     * @param backoff              重试退避策略，允许为 {@code null}，此时默认使用固定 1000ms
     * @param retryOnExceptions    允许触发重试的异常类型集合
     * @param abortOnExceptions    命中后立即终止重试的异常类型集合
     * @param condition            额外的表达式条件，允许为 {@code null}，表达式语法可参考
     *                             {@link Criteria}
     */
    @lombok.Builder(builderClassName = "Builder")
    private RetryPolicy(
            Integer maxRetries,
            Integer foregroundMaxRetries,
            Backoff backoff,
            @Singular("retryOn") Set<Class<? extends Throwable>> retryOnExceptions,
            @Singular("abortOn") Set<Class<? extends Throwable>> abortOnExceptions,
            String condition) {
        // Builder 输入保持可空，便于在这里集中处理默认值。
        this.maxRetries = maxRetries == null ? 2 : maxRetries;
        this.backoff = backoff == null ? Backoffs.fixed(1000) : backoff;

        if (this.maxRetries < -1) {
            throw new IllegalArgumentException(
                    "maxRetries must be greater than or equal to 0, or -1 (infinite retries)");
        }
        if (foregroundMaxRetries != null && foregroundMaxRetries < 0) {
            throw new IllegalArgumentException("foregroundMaxRetries must be greater than or equal to 0");
        }
        if (this.maxRetries != -1
                && foregroundMaxRetries != null
                && foregroundMaxRetries > this.maxRetries) {
            throw new IllegalArgumentException("foregroundMaxRetries must not be greater than maxRetries");
        }

        this.foregroundMaxRetries = foregroundMaxRetries;
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

    /**
     * 判断是否可以继续重试。
     *
     * @param executedAttempts 已执行且失败的总尝试次数（包含首次执行）
     * @param ex               本次尝试抛出的异常
     * @return true 表示允许继续重试
     */
    public boolean canRetry(int executedAttempts, Throwable ex) {
        if (maxRetries != -1 && executedAttempts > maxRetries) {
            return false;
        }

        Throwable cause = extractCause(ex);
        if (cause instanceof InterruptedException) {
            return false;
        }

        // 如果异常命中终止列表，立即停止
        if (!abortOnExceptions.isEmpty() && matches(cause, abortOnExceptions)) {
            return false;
        }

        // 如果配置了重试白名单且未命中，停止重试
        if (!retryOnExceptions.isEmpty() && !matches(cause, retryOnExceptions)) {
            return false;
        }

        // 执行额外的表达式条件判断
        if (condition != null && !condition.isEmpty()) {
            RetryContext contextData = new RetryContext(executedAttempts - 1, maxRetries, cause);
            MatchContext ctx = MatchContext.of(contextData);
            return Criteria.global().matches(condition, ctx);
        }

        return true;
    }

    /**
     * 计算下一次重试的延迟时间。
     *
     * @param currentAttempt 当前尝试次数
     * @return 延迟毫秒数
     */
    public long getDelayMillis(int currentAttempt) {
        return backoff.calculateMillis(currentAttempt);
    }

    private boolean matches(Throwable ex, Set<Class<? extends Throwable>> classes) {
        return classes.stream().anyMatch(clazz -> clazz.isAssignableFrom(ex.getClass()));
    }

    private Throwable extractCause(Throwable ex) {
        return RetryExceptionUtil.unwrapAndRestoreInterrupt(ex);
    }

    /**
     * 重试判定上下文，用于表达式计算。
     */
    @Getter
    public static class RetryContext {
        private final int retryCount;
        private final int maxRetries;
        private final Throwable cause;
        private final String message;

        public RetryContext(int retryCount, int maxRetries, Throwable cause) {
            this.retryCount = retryCount;
            this.maxRetries = maxRetries;
            this.cause = cause;
            // 预解析消息，降低表达式执行开销
            this.message = cause != null && cause.getMessage() != null ? cause.getMessage() : "";
        }
    }
}
