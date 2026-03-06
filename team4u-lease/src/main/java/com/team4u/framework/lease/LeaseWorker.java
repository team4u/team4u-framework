package com.team4u.framework.lease;

import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * 基于租约协议消费任务的 Worker 实现。
 * <p>
 * 该 Worker 采用“拉取（Polling）+ 租约（Leasing）”模式：
 * 1. 周期性从 {@link LeaseBackend} 获取待处理任务的租约。
 * 2. 获取成功后，启动心跳守护线程（续约）并执行业务逻辑。
 * 3. 根据执行结果（成功/失败/异常）回调后端进行状态确认、重试或标记失败。
 */
public class LeaseWorker implements Runnable, AutoCloseable {

    private static final Log log = LogFactory.get();

    private final LeaseBackend backend;
    private final LeaseTaskHandlerRegistry registry;
    private final LeaseWorkerPolicy policy;
    private final ScheduledExecutorService heartbeatExecutor;

    private volatile boolean running;
    private Thread workerThread;

    /**
     * 构造 LeaseWorker
     *
     * @param backend  租约后端存储
     * @param registry 任务处理器注册表
     * @param policy   Worker 运行策略配置
     */
    public LeaseWorker(LeaseBackend backend, LeaseTaskHandlerRegistry registry, LeaseWorkerPolicy policy) {
        this.backend = backend;
        this.registry = registry;
        this.policy = policy == null ? LeaseWorkerPolicy.builder().build() : policy;
        this.heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "lease-heartbeat-" + LeaseWorker.this.policy.getWorkerId());
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    /**
     * 以默认线程名启动 Worker
     */
    public synchronized void start() {
        start("lease-worker");
    }

    /**
     * 启动 Worker 轮询线程
     *
     * @param threadName 线程名称
     */
    public synchronized void start(String threadName) {
        if (running) {
            return;
        }
        running = true;
        workerThread = new Thread(this, threadName == null ? "lease-worker" : threadName);
        workerThread.setDaemon(true);
        workerThread.start();
    }

    /**
     * 停止 Worker。
     * <p>
     * 该操作会中断轮询线程并强行关闭心跳调度器。
     */
    public synchronized void shutdown() {
        running = false;
        if (workerThread != null) {
            workerThread.interrupt();
        }
        heartbeatExecutor.shutdownNow();
    }

    @Override
    public void close() {
        shutdown();
    }

    @Override
    public void run() {
        while (running && !Thread.currentThread().isInterrupted()) {
            LeaseGrant grant;
            try {
                grant = backend.acquire(policy.getWorkerId(), policy.getLeaseMillis(), policy.getPollWaitMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Throwable ex) {
                log.error("Lease worker acquire failed. workerId={}", policy.getWorkerId(), ex);
                sleepQuietly(policy.getPollWaitMillis());
                continue;
            }

            if (grant == null) {
                continue;
            }

            final HeartbeatTask heartbeatTask = createHeartbeatTask(grant);
            try {
                LeaseTaskHandler handler = registry.get(grant.getTaskType()).orElse(null);
                if (handler == null) {
                    handleMissingHandler(grant);
                } else {
                    if (heartbeatTask != null) {
                        heartbeatTask.start();
                    }
                    handler.handle(grant.getPayload());
                    backend.ack(grant.getTaskId(), grant.getWorkerId(), grant.getLeaseToken());
                }
            } catch (Throwable ex) {
                handleFailure(grant, ex);
            } finally {
                if (heartbeatTask != null) {
                    heartbeatTask.stop();
                }
            }
        }

        log.info("Lease worker stopped. workerId={}", policy.getWorkerId());
    }

    private void handleMissingHandler(LeaseGrant grant) {
        IllegalStateException ex = new IllegalStateException("LeaseTaskHandler not found. taskType=" + grant.getTaskType());
        if (policy.getMissingHandlerStrategy() == MissingHandlerStrategy.RETRY_LATER && policy.shouldRetry(grant.getAttemptCount())) {
            backend.retry(grant.getTaskId(), grant.getWorkerId(), grant.getLeaseToken(),
                    policy.nextDelayMillis(grant.getAttemptCount()), ex);
            return;
        }
        backend.fail(grant.getTaskId(), grant.getWorkerId(), grant.getLeaseToken(), ex);
    }

    private void handleFailure(LeaseGrant grant, Throwable ex) {
        log.error("Lease worker handle failed. taskId={}, taskType={}", grant.getTaskId(), grant.getTaskType(), ex);
        try {
            if (policy.shouldRetry(grant.getAttemptCount())) {
                backend.retry(grant.getTaskId(), grant.getWorkerId(), grant.getLeaseToken(),
                        policy.nextDelayMillis(grant.getAttemptCount()), ex);
            } else {
                backend.fail(grant.getTaskId(), grant.getWorkerId(), grant.getLeaseToken(), ex);
            }
        } catch (Throwable writeEx) {
            log.error("Lease worker write-back failed. taskId={}", grant.getTaskId(), writeEx);
        }
    }

    private HeartbeatTask createHeartbeatTask(LeaseGrant grant) {
        if (!policy.isHeartbeatEnabled()) {
            return null;
        }
        return new HeartbeatTask(backend, heartbeatExecutor, grant, policy.getHeartbeatIntervalMillis());
    }

    private void sleepQuietly(long millis) {
        if (millis <= 0L) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static class HeartbeatTask implements Runnable {
        private final LeaseBackend backend;
        private final ScheduledExecutorService executor;
        private final LeaseGrant grant;
        private final long intervalMillis;
        private volatile java.util.concurrent.ScheduledFuture<?> future;

        private HeartbeatTask(LeaseBackend backend,
                              ScheduledExecutorService executor,
                              LeaseGrant grant,
                              long intervalMillis) {
            this.backend = backend;
            this.executor = executor;
            this.grant = grant;
            this.intervalMillis = intervalMillis;
        }

        private void start() {
            future = executor.scheduleAtFixedRate(this, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
        }

        private void stop() {
            if (future != null) {
                future.cancel(true);
            }
        }

        @Override
        public void run() {
            backend.heartbeat(grant.getTaskId(), grant.getWorkerId(), grant.getLeaseToken(), intervalMillis);
        }
    }
}
