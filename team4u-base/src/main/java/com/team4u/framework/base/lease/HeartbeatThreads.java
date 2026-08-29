package com.team4u.framework.base.lease;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 共享心跳调度线程工厂（静态懒加载）
 * <p>
 * 未显式指定调度器时，{@link ScheduledHeartbeat} 使用本工厂提供的
 * daemon 单线程调度器池（命名 team4u-heartbeat-N），进程退出不被心跳线程阻塞。
 * 每个实例独占一个调度线程而非全局共享，避免多心跳之间相互饥饿；
 * 全部心跳停止后调度线程因空闲且为 daemon 而自动回收。
 * </p>
 *
 * @author jay.wu
 */
final class HeartbeatThreads {

    private static final AtomicInteger COUNTER = new AtomicInteger();

    private HeartbeatThreads() {
    }

    /**
     * 创建守护调度线程（命名 team4u-heartbeat-序号）
     *
     * @param name 线程名前缀，忽略（统一使用 team4u-heartbeat 前缀）
     * @return 守护线程工厂
     */
    static ThreadFactory daemonThreadFactory(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable,
                    "team4u-heartbeat-" + COUNTER.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    /**
     * 创建守护单线程调度器
     *
     * @param name 调度器名（用于线程命名）
     * @return daemon 单线程 ScheduledExecutorService
     */
    static ScheduledExecutorService newSingleThreadScheduler(String name) {
        return Executors.newSingleThreadScheduledExecutor(daemonThreadFactory(name));
    }

    /**
     * 静默关闭调度器（daemon 线程，无需等待终止）
     *
     * @param scheduler 待关闭调度器，可为 null
     */
    static void shutdownQuietly(ScheduledExecutorService scheduler) {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    /**
     * 线程级睡眠辅助（仅供内部测试钩子使用）
     *
     * @param millis 睡眠毫秒数
     */
    static void sleep(long millis) {
        try {
            TimeUnit.MILLISECONDS.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
