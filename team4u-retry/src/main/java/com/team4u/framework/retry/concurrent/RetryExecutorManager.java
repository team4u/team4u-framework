package com.team4u.framework.retry.concurrent;

import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 重试框架统一线程池管理器
 * <p>
 * 负责调度器和清理线程池的生命周期管理与优雅停机。
 */
public class RetryExecutorManager {

    private static final Log log = LogFactory.get();
    private static final RetryExecutorManager INSTANCE = new RetryExecutorManager();

    private volatile ScheduledExecutorService globalScheduler;
    private volatile ExecutorService globalCleanupExecutor;
    private volatile boolean isShutdown = false;

    private RetryExecutorManager() {
        // 1. 异步重试调度器：不使用守护线程，以支持在 JVM 退出前完成必要的任务
        this.globalScheduler = Executors.newScheduledThreadPool(
                Math.max(2, Runtime.getRuntime().availableProcessors()),
                new NamedThreadFactory("retry-scheduler-", false));

        // 2. 意图清理线程池：使用 DiscardPolicy 保护调用方不被阻塞
        // WAL 模式下，清理失败的任务会由后续的恢复机制处理
        this.globalCleanupExecutor = new ThreadPoolExecutor(
                2, Math.max(4, Runtime.getRuntime().availableProcessors()),
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(2000),
                new NamedThreadFactory("retry-cleanup-", false),
                (r, executor) -> {
                    // 降级策略：丢弃并记录告警，防止阻塞调用线程（如 Netty IO 线程）
                    log.warn("Retry cleanup task rejected! Queue is full. Relying on background recovery.");
                });

        // 注册 JVM 钩子作为非 Spring 环境下的优雅停机兜底
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));
    }

    /**
     * 重建内部线程池，使管理器从 shutdown 状态恢复为可用状态。
     * <p>
     * 该方法主要用于测试场景：当 Spring 上下文关闭触发 shutdown 后，
     * 后续非 Spring 的测试用例可以通过此方法重新激活线程池，
     * 避免因静态单例被永久关闭而导致任务提交失败。
     */
    public synchronized void reset() {
        if (!isShutdown) {
            return;
        }
        this.globalScheduler = Executors.newScheduledThreadPool(
                Math.max(2, Runtime.getRuntime().availableProcessors()),
                new NamedThreadFactory("retry-scheduler-", false));
        this.globalCleanupExecutor = new ThreadPoolExecutor(
                2, Math.max(4, Runtime.getRuntime().availableProcessors()),
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(2000),
                new NamedThreadFactory("retry-cleanup-", false),
                (r, executor) -> {
                    log.warn("Retry cleanup task rejected! Queue is full. Relying on background recovery.");
                });
        isShutdown = false;
        log.info("team4u-retry executors reset and ready.");
    }

    /**
     * 获取全局静态实例
     *
     * @return 线程池管理器单例
     */
    public static RetryExecutorManager global() {
        return INSTANCE;
    }

    /**
     * 获取异步任务调度器
     *
     * @return 调度线程池
     */
    public ScheduledExecutorService getScheduler() {
        if (isShutdown) {
            log.warn("Retry scheduler is shutting down, task submission may fail.");
        }
        return globalScheduler;
    }

    /**
     * 获取清理任务执行池
     *
     * @return 执行线程池
     */
    public Executor getCleanupExecutor() {
        if (isShutdown) {
            log.warn("Retry cleanup executor is shutting down, task submission may fail.");
        }
        return globalCleanupExecutor;
    }

    /**
     * 执行优雅停机逻辑
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
            // 为重试任务预留 10 秒收尾时间
            if (!globalScheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                log.warn("Forcing shutdown of retry scheduler...");
                globalScheduler.shutdownNow();
            }
            // 为清理任务预留 5 秒收尾时间
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

    /**
     * 命名的线程工厂实现
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
