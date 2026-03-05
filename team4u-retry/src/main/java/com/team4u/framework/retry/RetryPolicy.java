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
 * 这是一个不可变类，确保配置项在多线程环境下的绝对安全性。
 * 建议通过 {@link Builder} 来构建其实例。
 */
@Getter
public class RetryPolicy {
    /**
     * 全局最大尝试次数（包含首次,-1 表示无限）
     */
    private final int maxAttempts;
    /**
     * 仅控制前台内存重试预算；为空表示自动推导
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
     * 创建一个构建器，用于配置重试策略
     *
     * @return 构建器实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 判断当前情况是否允许继续重试
     *
     * @param currentAttempt 当前已尝试次数
     * @param ex             执行过程中抛出的异常
     * @return 如果满足所有条件允许重试返回true，否则返回false
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

        // 集成 team4u-criterion 进行高阶条件过滤，例如校验特定错误信息或组合重试策略
        if (conditionExpression != null && !conditionExpression.isEmpty()) {
            RetryContext contextData = new RetryContext(currentAttempt, getMaxAttempts(), cause);
            MatchContext ctx = MatchContext.of(contextData);
            return Criteria.global().matches(conditionExpression, ctx);
        }

        return true;
    }

    /**
     * 获取下次重试前需要等待的时间
     *
     * @param currentAttempt 当前已尝试次数
     * @return 等待时间的毫秒数
     */
    public long getDelayMillis(int currentAttempt) {
        return backoff.calculateMillis(currentAttempt);
    }

    /**
     * 检查当前异常类型是否匹配指定的异常集合
     */
    private boolean matches(Throwable ex, Set<Class<? extends Throwable>> classes) {
        return classes.stream().anyMatch(clazz -> clazz.isAssignableFrom(ex.getClass()));
    }

    /**
     * 剥离外层包装，提取真正的异常原因
     * <p>
     * 例如在异步操作中，真正的异常经常被包装在 CompletionException 中，
     * 在代理层则可能被包装在 UndeclaredThrowableException、InvocationTargetException 或
     * ExecutionException 中。
     */
    private Throwable extractCause(Throwable ex) {
        return RetryExceptionUtil.unwrap(ex);
    }

    /**
     * 重试上下文，用于承载每次尝试的变量抛给表达式引擎
     */
    @Getter
    public static class RetryContext {
        private final int attempt;
        /**
         * 全局最大尝试次数（-1 表示无限）
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
     * RetryPolicy 的构建器
     */
    public static class Builder {
        private final Set<Class<? extends Throwable>> retryOnExceptions = new HashSet<>();
        private final Set<Class<? extends Throwable>> abortOnExceptions = new HashSet<>();
        private int maxAttempts = 3;
        private Integer inMemoryAttempts;
        private Backoff backoff = Backoff.fixed(1000);
        private String conditionExpression;

        /**
         * 设置全局最大尝试次数（内存 + 后端总和，包含首次）。
         *
         * @param maxAttempts 全局最大尝试次数，包含首次请求
         * @return 构建器自身
         */
        public Builder maxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
            return this;
        }

        /**
         * 设置前台内存尝试次数预算（可选，不设置则自动推导）
         *
         * @param inMemoryAttempts 前台内存最大尝试次数，包含首次请求
         * @return 构建器自身
         */
        public Builder inMemoryAttempts(int inMemoryAttempts) {
            this.inMemoryAttempts = inMemoryAttempts;
            return this;
        }

        /**
         * 设置为无限次重试
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
         * @param backoff 基于不同模式计算延迟时间的策略对象
         * @return 构建器自身
         */
        public Builder backoff(Backoff backoff) {
            this.backoff = backoff;
            return this;
        }

        /**
         * 声明允许触发重试的异常类型
         *
         * @param exceptions 遇到这些异常及其子类时允许重试
         * @return 构建器自身
         */
        @SafeVarargs
        public final Builder retryOn(Class<? extends Throwable>... exceptions) {
            this.retryOnExceptions.addAll(Arrays.asList(exceptions));
            return this;
        }

        /**
         * 声明会终止重试的异常类型
         *
         * @param exceptions 遇到这些异常及其子类时，不再尝试重试，立刻中断
         * @return 构建器自身
         */
        @SafeVarargs
        public final Builder abortOn(Class<? extends Throwable>... exceptions) {
            this.abortOnExceptions.addAll(Arrays.asList(exceptions));
            return this;
        }

        /**
         * 引入 Criterion 表达式控制高级策略
         * 示例: .condition("message contains 'timeout' && $attempt < 3")
         *
         * @param expression Criterion 能够解析的判断表达式
         * @return 构建器自身
         */
        public Builder condition(String expression) {
            this.conditionExpression = expression;
            return this;
        }

        /**
         * 构建出不可变的 RetryPolicy 实例
         *
         * @return 配置好的重试策略
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
