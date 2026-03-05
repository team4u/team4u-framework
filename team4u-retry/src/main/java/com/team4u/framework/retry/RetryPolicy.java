package com.team4u.framework.retry;

import com.team4u.framework.criterion.Criteria;
import com.team4u.framework.criterion.MatchContext;
import com.team4u.framework.retry.backoff.Backoff;
import lombok.Getter;

import java.util.Arrays;
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
    private final int totalAttempts;
    /**
     * 仅控制前台内存重试预算；为空表示自动推导
     */
    private final Integer inMemoryAttempts;
    private final Backoff backoff;
    private final Set<Class<? extends Throwable>> retryOnExceptions;
    private final Set<Class<? extends Throwable>> abortOnExceptions;
    private final String conditionExpression;

    private RetryPolicy(Builder builder) {
        this.totalAttempts = builder.totalAttempts;
        this.inMemoryAttempts = builder.inMemoryAttempts;
        this.backoff = builder.backoff;
        this.retryOnExceptions = builder.retryOnExceptions;
        this.abortOnExceptions = builder.abortOnExceptions;
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
        if (currentAttempt >= totalAttempts && totalAttempts != -1) {
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
            RetryContext contextData = new RetryContext(currentAttempt, totalAttempts, cause);
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
     * 在代理层则可能被包装在 UndeclaredThrowableException、InvocationTargetException 或 ExecutionException 中。
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
        private final int totalAttempts;
        private final int maxAttempts;
        private final Throwable cause;
        private final String message;

        public RetryContext(int attempt, int totalAttempts, Throwable cause) {
            this.attempt = attempt;
            this.totalAttempts = totalAttempts;
            this.maxAttempts = totalAttempts;
            this.cause = cause;
            this.message = cause != null ? cause.getMessage() : null;
        }
    }

    /**
     * RetryPolicy 的构建器
     */
    public static class Builder {
        private final Set<Class<? extends Throwable>> retryOnExceptions = new HashSet<>();
        private final Set<Class<? extends Throwable>> abortOnExceptions = new HashSet<>();
        private int totalAttempts = 3;
        private Integer inMemoryAttempts;
        private Backoff backoff = Backoff.fixed(1000);
        private String conditionExpression;

        /**
         * 设置全局最大尝试次数（内存 + 后端总和，包含首次）
         *
         * @param totalAttempts 全局最大尝试次数，包含首次请求
         * @return 构建器自身
         */
        public Builder totalAttempts(int totalAttempts) {
            this.totalAttempts = totalAttempts;
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
            this.totalAttempts = -1;
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
            if (totalAttempts == 0 || totalAttempts < -1) {
                throw new IllegalArgumentException("totalAttempts 必须大于 0 或者为 -1（无限重试）");
            }
            if (inMemoryAttempts != null && inMemoryAttempts <= 0) {
                throw new IllegalArgumentException("inMemoryAttempts 必须大于 0");
            }
            if (totalAttempts != -1 && inMemoryAttempts != null && inMemoryAttempts > totalAttempts) {
                throw new IllegalArgumentException("inMemoryAttempts 不能大于 totalAttempts");
            }
            return new RetryPolicy(this);
        }
    }
}
