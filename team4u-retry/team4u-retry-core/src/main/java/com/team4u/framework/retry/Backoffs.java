package com.team4u.framework.retry;

import cn.hutool.core.lang.Assert;
import com.team4u.framework.retry.backoff.BackoffRegistry;
import com.team4u.framework.retry.config.BackoffConfig;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.util.HashMap;
import java.util.Map;

/**
 * 退避策略统一门面类
 * <p>
 * 提供快捷创建方法、类型安全 Builder 以及通用扩展 Builder。
 */
public final class Backoffs {

    private Backoffs() {
    }

    /**
     * 创建固定延迟退避策略
     */
    public static Backoff fixed(long delayMillis) {
        return fixedBuilder().delay(delayMillis).build();
    }

    /**
     * 创建等差递增延迟退避策略
     */
    public static Backoff increment(long initialDelayMillis, long stepMillis) {
        return incrementBuilder().initialDelay(initialDelayMillis).stepMillis(stepMillis).build();
    }

    /**
     * 创建指数级退避策略
     */
    public static Backoff exponential(long initialDelayMillis, double multiplier, long maxDelayMillis) {
        return exponentialBuilder()
                .initialDelay(initialDelayMillis)
                .multiplier(multiplier)
                .maxDelay(maxDelayMillis)
                .build();
    }

    /**
     * 创建带随机抖动的指数级退避策略
     */
    public static Backoff exponentialJitter(long initialDelayMillis, double multiplier, long maxDelayMillis) {
        return exponentialJitterBuilder()
                .initialDelay(initialDelayMillis)
                .multiplier(multiplier)
                .maxDelay(maxDelayMillis)
                .build();
    }

    /**
     * 创建固定延迟构建器
     */
    public static FixedBuilder fixedBuilder() {
        return new FixedBuilder();
    }

    /**
     * 创建等差递增延迟构建器
     */
    public static IncrementBuilder incrementBuilder() {
        return new IncrementBuilder();
    }

    /**
     * 创建指数级延迟构建器
     */
    public static ExponentialBuilder exponentialBuilder() {
        return new ExponentialBuilder();
    }

    /**
     * 创建带随机抖动的指数级延迟构建器
     */
    public static ExponentialJitterBuilder exponentialJitterBuilder() {
        return new ExponentialJitterBuilder();
    }

    /**
     * 创建通用退避策略构建器
     *
     * @param type 策略类型标识
     */
    public static GenericBuilder builder(String type) {
        return new GenericBuilder(type);
    }

    /**
     * 固定延迟构建器
     */
    @Setter
    @Accessors(fluent = true, chain = true)
    public static final class FixedBuilder {
        private long delay = 1000;

        public Backoff build() {
            Assert.isTrue(delay >= 0, "delay must be >= 0");
            return builder("fixed")
                    .param("delay", delay)
                    .build();
        }
    }

    /**
     * 等差递增延迟构建器
     */
    @Setter
    @Accessors(fluent = true, chain = true)
    public static final class IncrementBuilder {
        private long initialDelay = 1000;
        private long stepMillis = 500;

        public Backoff build() {
            Assert.isTrue(initialDelay >= 0, "initialDelay must be >= 0");
            Assert.isTrue(stepMillis >= 0, "stepMillis must be >= 0");
            return builder("increment")
                    .param("initialDelay", initialDelay)
                    .param("stepMillis", stepMillis)
                    .build();
        }
    }

    /**
     * 指数类构建器基类
     */
    @Setter
    @Accessors(fluent = true, chain = true)
    public abstract static class BaseExponentialBuilder<T extends BaseExponentialBuilder<T>> {
        protected long initialDelay = 1000;
        protected double multiplier = 2.0;
        protected long maxDelay = 30000;

        protected void validate() {
            Assert.isTrue(initialDelay >= 0, "initialDelay must be >= 0");
            Assert.isTrue(multiplier > 0, "multiplier must be > 0");
            Assert.isTrue(maxDelay >= initialDelay, "maxDelay must be >= initialDelay");
        }

        protected abstract String type();

        public Backoff build() {
            validate();
            return builder(type())
                    .param("initialDelay", initialDelay)
                    .param("multiplier", multiplier)
                    .param("maxDelay", maxDelay)
                    .build();
        }
    }

    /**
     * 指数级延迟构建器
     */
    public static final class ExponentialBuilder extends BaseExponentialBuilder<ExponentialBuilder> {
        @Override
        protected String type() {
            return "exponential";
        }
    }

    /**
     * 带随机抖动的指数级延迟构建器
     */
    public static final class ExponentialJitterBuilder extends BaseExponentialBuilder<ExponentialJitterBuilder> {
        @Override
        protected String type() {
            return "exponentialjitter";
        }
    }

    /**
     * 通用退避策略构建器
     */
    public static final class GenericBuilder {
        private final String type;
        private final Map<String, Object> params = new HashMap<>();

        public GenericBuilder(String type) {
            this.type = type;
        }

        public GenericBuilder param(String name, Object value) {
            params.put(name, value);
            return this;
        }

        public Backoff build() {
            BackoffConfig config = new BackoffConfig();
            config.setType(type);
            config.setParams(params);
            return BackoffRegistry.global().get(type)
                    .map(factory -> factory.create(config))
                    .orElseThrow(() -> new IllegalArgumentException("Unsupported backoff type: " + type));
        }
    }
}
