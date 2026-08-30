package com.team4u.framework.log.pipeline.interceptor;

import com.team4u.framework.base.cache.CacheUtil;
import com.team4u.framework.base.cache.TimedCache;
import com.team4u.framework.log.core.LogEvent;
import com.team4u.framework.log.pipeline.LogInterceptor;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;

/**
 * Exception-rate limiter with an injectable, hot-readable threshold supplier.
 */
public class RateLimitInterceptor implements LogInterceptor {

    public static final int DEFAULT_ERROR_LIMIT_PER_SECOND = 10;

    private static final RateLimitInterceptor INSTANCE = new RateLimitInterceptor();

    private final TimedCache<String, AtomicInteger> errorCounter = CacheUtil.newTimedCache(1000);
    private volatile IntSupplier errorLimitPerSecond = () -> DEFAULT_ERROR_LIMIT_PER_SECOND;

    private RateLimitInterceptor() {
    }

    /**
     * Returns the legacy shared instance. New engines use {@link #create()} so mutable
     * rate state is never shared between independently built engines.
     */
    public static RateLimitInterceptor getInstance() {
        return INSTANCE;
    }

    public static RateLimitInterceptor create() {
        return new RateLimitInterceptor();
    }

    public void setErrorLimitPerSecond(IntSupplier supplier) {
        this.errorLimitPerSecond = supplier != null
                ? supplier
                : () -> DEFAULT_ERROR_LIMIT_PER_SECOND;
    }

    public void resetErrorLimitPerSecond() {
        this.errorLimitPerSecond = () -> DEFAULT_ERROR_LIMIT_PER_SECOND;
    }

    /**
     * Clears counters only. An explicitly configured supplier remains active so engine
     * resets do not remove live governance policy.
     */
    @Override
    public synchronized void stop() {
        errorCounter.clear();
    }

    @Override
    public int priority() {
        return LOW;
    }

    @Override
    public synchronized boolean handle(LogEvent event) {
        if (event.getException() == null) {
            return true;
        }

        int errorLimitPerSecond = currentLimit();
        String signature = buildSignature(event);
        AtomicInteger count = errorCounter.getOrCreate(signature, () -> new AtomicInteger(0));
        int currentCount = count.incrementAndGet();

        if (currentCount > errorLimitPerSecond) {
            event.setSuppressed(true);
            if (currentCount == errorLimitPerSecond + 1) {
                LoggerFactory.getLogger("TEAM4U-LOG-LIMITER")
                        .warn("[RateLimit] Suppressed logs for action: {}", signature);
            }
            return false;
        }

        return true;
    }

    private int currentLimit() {
        int limit;
        try {
            limit = errorLimitPerSecond.getAsInt();
        } catch (RuntimeException e) {
            limit = DEFAULT_ERROR_LIMIT_PER_SECOND;
        }
        return Math.max(0, limit);
    }

    private String buildSignature(LogEvent event) {
        String loggerName = event.getLoggerName() != null ? event.getLoggerName() : "";
        String action = event.getAction() != null ? event.getAction() : "";
        return loggerName + "|" + action + "|" + event.getException().getClass().getName();
    }
}
