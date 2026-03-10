package com.team4u.framework.retry.concurrent;

import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 重试框架线程池管理器
 * <p>
 * 维护重试引擎内部使用的全局线程池资源，包括用于异步延迟调度的 {@link ScheduledExecutorService}
 * 以及执行任务状态清理（如 complete/cancel 后端意向）的 {@link ExecutorService}。
 */
public class RetryExecutorManager {

    private static final Log log = LogFactory.get();
    private static final RetryExecutorManager INSTANCE = new RetryExecutorManager();
    private static final String DAEMON_PROPERTY = "team4u.retry.executors.daemon";
    static final String SHUTDOWN_HOOK_ENABLED_PROPERTY = "team4u.retry.executors.shutdownHook.enabled";

    private volatile ScheduledExecutorService globalScheduler;
    private volatile ExecutorService globalCleanupExecutor;
    private volatile boolean isShutdown = false;

    private RetryExecutorManager() {
        boolean daemon = isDaemonExecutors();

        this.globalScheduler = Executors.newScheduledThreadPool(
                Math.max(2, Runtime.getRuntime().availableProcessors()),
                new NamedThreadFactory("retry-scheduler-", daemon));

        this.globalCleanupExecutor = new ThreadPoolExecutor(
                2, Math.max(4, Runtime.getRuntime().availableProcessors()),
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(2000),
                new NamedThreadFactory("retry-cleanup-", daemon),
                (r, executor) -> {
                    log.warn("Retry cleanup task rejected! Queue is full. Relying on background recovery.");
                });

        if (isShutdownHookEnabled()) {
            Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "team4u-retry-shutdown"));
        }
    }

    /**
     * 获取单例实例
     *
     * @return 线程池管理器实例
     */
    public static RetryExecutorManager global() {
        return INSTANCE;
    }

    /**
     * 重置线程池
     * <p>
     * 在停机状态下重新初始化线程池，主要用于测试场景。
     */
    public synchronized void reset() {
        if (!isShutdown) {
            return;
        }
        boolean daemon = isDaemonExecutors();
        this.globalScheduler = Executors.newScheduledThreadPool(
                Math.max(2, Runtime.getRuntime().availableProcessors()),
                new NamedThreadFactory("retry-scheduler-", daemon));
        this.globalCleanupExecutor = new ThreadPoolExecutor(
                2, Math.max(4, Runtime.getRuntime().availableProcessors()),
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(2000),
                new NamedThreadFactory("retry-cleanup-", daemon),
                (r, executor) -> {
                    log.warn("Retry cleanup task rejected! Queue is full. Relying on background recovery.");
                });
        isShutdown = false;
        log.info("team4u-retry executors reset and ready.");
    }

    /**
     * 获取异步调度线程池
     *
     * @return 调度线程池实例
     */
    public ScheduledExecutorService getScheduler() {
        if (isShutdown) {
            log.warn("Retry scheduler is shutting down, task submission may fail.");
        }
        return globalScheduler;
    }

    /**
     * 获取清理任务执行线程池
     *
     * @return 执行线程池实例
     */
    public Executor getCleanupExecutor() {
        if (isShutdown) {
            log.warn("Retry cleanup executor is shutting down, task submission may fail.");
        }
        return globalCleanupExecutor;
    }

    /**
     * 执行停机逻辑
     */
    public synchronized void shutdown() {
        if (isShutdown) {
            return;
        }
        isShutdown = true;
        log.info("Shutting down team4u-retry executors...");

        globalScheduler.shutdown();
        globalCleanupExecutor.shutdown();

        try {
            if (!globalScheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                log.warn("Forcing shutdown of retry scheduler...");
                globalScheduler.shutdownNow();
            }
            if (!globalCleanupExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("Forcing shutdown of retry cleanup executor...");
                globalCleanupExecutor.shutdownNow();
            }
            log.info("team4u-retry executors shutdown completely.");
        } catch (InterruptedException e) {
            log.error("Interrupted during team4u-retry shutdown.", e);
            globalScheduler.shutdownNow();
            globalCleanupExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private boolean isDaemonExecutors() {
        return Boolean.parseBoolean(System.getProperty(DAEMON_PROPERTY, "false"));
    }

    static boolean isShutdownHookEnabled() {
        return Boolean.parseBoolean(System.getProperty(SHUTDOWN_HOOK_ENABLED_PROPERTY, "true"));
    }

    /**
     * 命名的线程工厂
     */
    private static class NamedThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger(1);
        private final String prefix;
        private final boolean daemon;

        public NamedThreadFactory(String prefix, boolean daemon) {
            this.prefix = prefix;
            this.daemon = daemon;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, prefix + counter.getAndIncrement());
            t.setDaemon(daemon);
            if (t.getPriority() != Thread.NORM_PRIORITY) {
                t.setPriority(Thread.NORM_PRIORITY);
            }
            return t;
        }
    }
}
