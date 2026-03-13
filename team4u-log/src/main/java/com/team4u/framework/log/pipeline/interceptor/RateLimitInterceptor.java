package com.team4u.framework.log.pipeline.interceptor;

import com.team4u.framework.base.util.CacheUtil;
import com.team4u.framework.base.util.cache.TimedCache;
import com.team4u.framework.log.config.FinOpsConfigRepository;
import com.team4u.framework.log.core.LogEvent;
import com.team4u.framework.log.pipeline.LogInterceptor;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 限流拦截器
 * <p>
 * 基于异常特征执行限流逻辑，防止日志洪峰冲击存储资源。
 */
public class RateLimitInterceptor implements LogInterceptor {

    private static final RateLimitInterceptor INSTANCE = new RateLimitInterceptor();

    /**
     * 缓存 1 秒内的错误频次
     */
    private final TimedCache<String, AtomicInteger> errorCounter = CacheUtil.newTimedCache(1000);

    private RateLimitInterceptor() {
        reset();
    }

    /**
     * 获取限流拦截器单例实例
     *
     * @return RateLimitInterceptor 实例
     */
    public static RateLimitInterceptor getInstance() {
        return INSTANCE;
    }

    @Override
    public void reset() {
        errorCounter.clear();
    }

    @Override
    public int priority() {
        return LOW;
    }

    @Override
    public boolean handle(LogEvent event) {
        // 仅对带有异常信息的日志进行频控
        if (event.getException() == null) {
            return true;
        }

        // 获取当前实时限流阈值
        int errorLimitPerSecond = FinOpsConfigRepository.getInstance().get().getErrorLimitPerSecond();

        // 生成特征索引：动作 + 异常类名
        String signature = event.getAction() + "|" + event.getException().getClass().getName();

        AtomicInteger count = errorCounter.getOrCreate(signature, () -> new AtomicInteger(0));

        int currentCount = count.incrementAndGet();

        // 执行限流判定
        if (currentCount > errorLimitPerSecond) {
            event.setSuppressed(true);

            // 首次超过阈值时输出提示，使用 SLF4J 避免产生死循环，并采用独立的 LoggerName
            if (currentCount == (errorLimitPerSecond + 1)) {
                LoggerFactory.getLogger("TEAM4U-LOG-LIMITER")
                        .warn("[RateLimit] Suppressed logs for action: {}", signature);
            }
            return false;
        }

        return true;
    }
}
