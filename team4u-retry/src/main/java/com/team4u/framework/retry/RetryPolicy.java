package com.team4u.framework.retry;

import com.team4u.framework.criterion.Criteria;
import com.team4u.framework.criterion.MatchContext;
import com.team4u.framework.retry.backoff.Backoff;
import lombok.Getter;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 重试策略配置
 * <p>
 * 不可变类，用于定义任务重试的核心规则。
 * 推荐使用 {@link Builder} 进行构建。
 */
@Getter
public class RetryPolicy {
    /**
     * 最大尝试次数，包含首次请求（-1 表示无限制）
     */
    private final int maxAttempts;
    /**
     * 内存重试配额（可选，未设置时将自动推导）
     */
    private final Integer inMemoryAttempts;
    private final Backoff backoff;
    private final Set<Class<? extends Throwable>> retryOnExceptions;
    private final Set<Class<? extends Throwable>> abortOnExceptions;
    private final String conditionExpression;

    private RetryPolicy(Builder builder) {
        this.maxAttempts = builder.maxAttempts;
        this.inMemoryAttempts = builder.inMemoryAttempts;
        this.backoff = builder.backoff;
        this.retryOnExceptions = Collections.unmodifiableSet(new HashSet<>(builder.retryOnExceptions));
        this.abortOnExceptions = Collections.unmodifiableSet(new HashSet<>(builder.abortOnExceptions));
        this.conditionExpression = builder.conditionExpression;
    }

    /**
     * 创建重试策略构建器
     *
     * @return 构建器实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 检查是否允许继续进行下一次尝试
     *
     * @param currentAttempt 当前尝试次数
     * @param ex             当前发生的异常
     * @return 是否满足重试条件
     */
    public boolean canRetry(int currentAttempt, Throwable ex) {
        if (currentAttempt >= maxAttempts && maxAttempts != -1) {
            return false;
        }

        Throwable cause = extractCause(ex);

        if (!abortOnExceptions.isEmpty() && matches(cause, abortOnExceptions)) {
            return false;
        }

        if (!retryOnExceptions.isEmpty() && !matches(cause, retryOnExceptions)) {
            return false;
        }

        // 使用集成表达式引擎进行高级条件判定（如异常信息内容匹配等）
        if (conditionExpression != null && !conditionExpression.isEmpty()) {
            RetryContext contextData = new RetryContext(currentAttempt, getMaxAttempts(), cause);
            MatchContext ctx = MatchContext.of(contextData);
            return Criteria.global().matches(conditionExpression, ctx);
        }

        return true;
    }

    /**
     * 计算下次尝试前的等待时间
     *
     * @param currentAttempt 当前完成的尝试次数（从 1 开始）
     * @return 延迟等待毫秒数
     */
    public long getDelayMillis(int currentAttempt) {
        return backoff.calculateMillis(currentAttempt);
    }

    /**
     * 校验异常类型是否命中指定集合
     */
    private boolean matches(Throwable ex, Set<Class<? extends Throwable>> classes) {
        return classes.stream().anyMatch(clazz -> clazz.isAssignableFrom(ex.getClass()));
    }

    /**
     * 提取根因异常，剥离各层包装（如异步包装异常等）
     */
    private Throwable extractCause(Throwable ex) {
        return RetryExceptionUtil.unwrap(ex);
    }

    /**
     * 重试运行上下文，用于承载策略判定所需的变量
     */
    @Getter
    public static class RetryContext {
        private final int attempt;
        /**
         * 全局最大尝试次数（-1 表示无限制）
         */
        private final int maxAttempts;
        private final Throwable cause;
        private final String message;

        public RetryContext(int attempt, int maxAttempts, Throwable cause) {
            this.attempt = attempt;
            this.maxAttempts = maxAttempts;
            this.cause = cause;
            this.message = (cause != null && cause.getMessage() != null) ? cause.getMessage() : "";
        }

    }

    /**
     * RetryPolicy 构建器
     */
    public static class Builder {
        private final Set<Class<? extends Throwable>> retryOnExceptions = new HashSet<>();
        private final Set<Class<? extends Throwable>> abortOnExceptions = new HashSet<>();
        private int maxAttempts = 3;
        private Integer inMemoryAttempts;
        private Backoff backoff = Backoff.fixed(1000);
        private String conditionExpression;

        /**
         * 设置最大尝试次数（包含首次请求）
         *
         * @param maxAttempts 最大次数
         * @return 构建器自身
         */
        public Builder maxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
            return this;
        }

        /**
         * 设置内存重试尝试次数
         *
         * @param inMemoryAttempts 内存尝试配额
         * @return 构建器自身
         */
        public Builder inMemoryAttempts(int inMemoryAttempts) {
            this.inMemoryAttempts = inMemoryAttempts;
            return this;
        }

        /**
         * 设置为无限尝试模式
         *
         * @return 构建器自身
         */
        public Builder infiniteAttempts() {
            this.maxAttempts = -1;
            return this;
        }

        /**
         * 设置退避策略
         *
         * @param backoff 退避计算规则
         * @return 构建器自身
         */
        public Builder backoff(Backoff backoff) {
            this.backoff = backoff;
            return this;
        }

        /**
         * 指定允许触发重试的异常类型
         *
         * @param exceptions 触发异常集合
         * @return 构建器自身
         */
        @SafeVarargs
        public final Builder retryOn(Class<? extends Throwable>... exceptions) {
            this.retryOnExceptions.addAll(Arrays.asList(exceptions));
            return this;
        }

        /**
         * 指定直接终止重试的异常类型
         *
         * @param exceptions 终止异常集合
         * @return 构建器自身
         */
        @SafeVarargs
        public final Builder abortOn(Class<? extends Throwable>... exceptions) {
            this.abortOnExceptions.addAll(Arrays.asList(exceptions));
            return this;
        }

        /**
         * 设置高级策略判定表达式
         * 示例: .condition("message contains 'timeout' && $attempt < 3")
         *
         * @param expression 逻辑表达式
         * @return 构建器自身
         */
        public Builder condition(String expression) {
            this.conditionExpression = expression;
            return this;
        }

        /**
         * 构造 RetryPolicy 实例
         *
         * @return 重试策略实例
         */
        public RetryPolicy build() {
            if (maxAttempts == 0 || maxAttempts < -1) {
                throw new IllegalArgumentException("maxAttempts must be greater than 0 or -1 (infinite retries)");
            }
            if (inMemoryAttempts != null && inMemoryAttempts <= 0) {
                throw new IllegalArgumentException("inMemoryAttempts must be greater than 0");
            }
            if (maxAttempts != -1 && inMemoryAttempts != null && inMemoryAttempts > maxAttempts) {
                throw new IllegalArgumentException("inMemoryAttempts must not be greater than maxAttempts");
            }
            return new RetryPolicy(this);
        }
    }
}
