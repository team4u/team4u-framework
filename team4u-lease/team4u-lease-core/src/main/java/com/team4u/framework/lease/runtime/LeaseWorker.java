package com.team4u.framework.lease.runtime;

import com.team4u.framework.lease.api.LeaseRuntimeClient;
import com.team4u.framework.lease.enums.LeaseRuntimeResult;
import com.team4u.framework.lease.enums.LeaseTaskFailureReason;
import com.team4u.framework.lease.enums.MissingHandlerStrategy;
import com.team4u.framework.lease.handler.LeaseLifecycleAwareTaskHandler;
import com.team4u.framework.lease.handler.LeaseTaskHandler;
import com.team4u.framework.lease.handler.LeaseTaskHandlerRegistry;
import com.team4u.framework.lease.model.*;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 分布式任务工作者实现
 * <p>
 * 该类是租约系统的核心执行引擎，负责：
 * 1. 周期性地从 {@link LeaseRuntimeClient} 抢占（Acquire）待处理任务。
 * 2. 维护已持有任务的租约心跳（Heartbeat），防止执行期间租约过期。
 * 3. 协调 {@link LeaseTaskHandler} 执行具体的业务逻辑。
 * 4. 根据执行结果进行确认（Ack）、重试（Retry）或失败（Fail）回写。
 */
public class LeaseWorker implements Runnable, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LeaseWorker.class);

    private final LeaseRuntimeClient runtimeClient;
    private final LeaseTaskHandlerRegistry registry;
    private final LeaseWorkerPolicy policy;
    private final ScheduledExecutorService heartbeatExecutor;
    private final AtomicBoolean processingTask = new AtomicBoolean(false);
    private final AtomicBoolean heartbeatExecutorShutdown = new AtomicBoolean(false);

    private volatile boolean running;
    private volatile boolean shutdown;
    private Thread workerThread;

    public LeaseWorker(LeaseRuntimeClient runtimeClient, LeaseTaskHandlerRegistry registry, LeaseWorkerPolicy policy) {
        this.runtimeClient = runtimeClient;
        this.registry = registry;
        this.policy = policy == null ? LeaseWorkerPolicy.builder().build() : policy;
        this.heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "lease-heartbeat-" + this.policy.getWorkerId());
            thread.setDaemon(true);
            return thread;
        });
    }

    public synchronized void start() {
        start(null);
    }

    /**
     * 以指定线程名异步启动工作者
     */
    public synchronized void start(String threadName) {
        if (shutdown) {
            throw new IllegalStateException("LeaseWorker cannot be restarted after shutdown");
        }
        if (running) {
            return;
        }
        running = true;
        workerThread = new Thread(this, threadName == null ? "lease-worker" : threadName);
        workerThread.setDaemon(true);
        workerThread.start();
    }

    public void shutdown() {
        shutdownGracefully(policy.getLeaseMillis());
    }

    public boolean shutdownGracefully(long timeoutMillis) {
        Thread threadToJoin;
        synchronized (this) {
            shutdown = true;
            running = false;
            threadToJoin = workerThread;
            if (threadToJoin != null && !processingTask.get()) {
                threadToJoin.interrupt();
            }
        }

        boolean workerStopped = waitForWorker(threadToJoin, timeoutMillis);
        if (workerStopped) {
            shutdownHeartbeatExecutor();
            waitForHeartbeatExecutor(timeoutMillis);
        }
        return workerStopped;
    }

    public synchronized void shutdownNow() {
        shutdown = true;
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

    /**
     * 工作者主循环
     * <p>
     * 持续抢占并执行任务，直到被停止或线程中断。
     */
    @Override
    public void run() {
        try {
            while (running && !Thread.currentThread().isInterrupted()) {
                LeaseGrant grant = acquireNextGrant();
                if (grant == null) {
                    continue;
                }

                processingTask.set(true);
                HeartbeatTask heartbeatTask = createHeartbeatTask(grant);
                try {
                    LeaseTaskHandler handler = registry.get(grant.getQueue(), grant.getTaskType()).orElse(null);
                    if (handler == null) {
                        handleMissingHandler(grant);
                        continue;
                    }
                    LeaseExecutionContext executionContext = toExecutionContext(handler, grant, heartbeatTask);
                    if (heartbeatTask != null) {
                        heartbeatTask.start();
                    }
                    if (handler instanceof LeaseLifecycleAwareTaskHandler) {
                        executeLifecycleAwareHandler((LeaseLifecycleAwareTaskHandler) handler,
                                grant,
                                (LeaseLifecycleExecutionContext) executionContext);
                    } else {
                        handler.handle(executionContext);
                        handleWriteResult("close", grant,
                                runtimeClient.close(grant.getHandle(), LeaseCloseRequest.succeeded()));
                    }
                } catch (Exception ex) {
                    handleFailure(grant, ex);
                } finally {
                    if (heartbeatTask != null) {
                        heartbeatTask.stop();
                    }
                    processingTask.set(false);
                    if (shutdown && workerThread != null) {
                        workerThread.interrupt();
                    }
                }
            }
        } finally {
            shutdownHeartbeatExecutor();
            log.info("Lease worker stopped. workerId={}", policy.getWorkerId());
        }
    }

    private void shutdownHeartbeatExecutor() {
        if (heartbeatExecutorShutdown.compareAndSet(false, true)) {
            heartbeatExecutor.shutdown();
        }
    }

    /**
     * 尝试抢占下一个可用的任务租约
     */
    private LeaseGrant acquireNextGrant() {
        try {
            Set<LeaseSubscription> subscriptions = registry.subscriptions();
            if (subscriptions.isEmpty()) {
                sleepQuietly(policy.getPollWaitMillis());
                return null;
            }
            return runtimeClient.acquire(LeaseAcquireRequest.builder()
                    .workerId(policy.getWorkerId())
                    .leaseMillis(policy.getLeaseMillis())
                    .waitTimeoutMillis(policy.getPollWaitMillis())
                    .subscriptions(subscriptions)
                    .build());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception ex) {
            log.error("Lease worker acquire failed. workerId={}", policy.getWorkerId(), ex);
            sleepQuietly(policy.getPollWaitMillis());
            return null;
        }
    }

    /**
     * 将租约授予凭据转换为业务执行上下文
     * <p>
     * 根据处理器的类型（普通型或生命周期感知型）返回不同实现的 Context。
     */
    private LeaseExecutionContext toExecutionContext(LeaseTaskHandler handler,
                                                     LeaseGrant grant,
                                                     HeartbeatTask heartbeatTask) {
        Runnable heartbeatRequester = heartbeatTask == null ? null : heartbeatTask::requestNow;

        // 如果处理器支持显式生命周期管理，构造增强型的上下文实例
        if (handler instanceof LeaseLifecycleAwareTaskHandler) {
            return new LeaseLifecycleExecutionContext(grant, heartbeatRequester, runtimeClient);
        }

        // 普通处理器使用基础执行上下文
        return LeaseExecutionContext.builder()
                .taskId(grant.getTaskId())
                .queue(grant.getQueue())
                .taskType(grant.getTaskType())
                .payload(grant.getPayload())
                .deliveryCount(grant.getDeliveryCount())
                .failureCount(grant.getFailureCount())
                .attributes(grant.getAttributes())
                .createdAtMillis(grant.getCreatedAtMillis())
                .visibleAtMillis(grant.getVisibleAtMillis())
                .leaseExpiresAtMillis(grant.getLeaseExpiresAtMillis())
                .heartbeatRequester(heartbeatRequester)
                .build();
    }

    private void executeLifecycleAwareHandler(LeaseLifecycleAwareTaskHandler handler,
                                              LeaseGrant grant,
                                              LeaseLifecycleExecutionContext context) throws Exception {
        // 执行具备生命周期感知能力的业务逻辑
        handler.handleLifecycle(context);

        // 如果处理器在方法内部已调用了 context.close() 或 context.release()，则直接返回
        if (context.isLifecycleHandled()) {
            return;
        }

        // 契约保护：如果处理器未显式声明生命周期结束，强制将其标记为失败，避免任务僵死
        handleWriteResult("close", grant,
                runtimeClient.close(grant.getHandle(),
                        LeaseCloseRequest.failed(
                                LeaseTaskFailureReason.HANDLER_CONTRACT_VIOLATION,
                                "LeaseLifecycleAwareTaskHandler executed without close/release")));
    }

    private void handleMissingHandler(LeaseGrant grant) {
        IllegalStateException ex = new IllegalStateException("LeaseTaskHandler not found. queue="
                + grant.getQueue() + ", taskType=" + grant.getTaskType());
        if (policy.getMissingHandlerStrategy() == MissingHandlerStrategy.RETRY_LATER) {
            log.warn("Lease worker released task because handler was not found. taskId={}, queue={}, taskType={}",
                    grant.getTaskId(), grant.getQueue(), grant.getTaskType());
            try {
                handleWriteResult("release", grant,
                        runtimeClient.release(grant.getHandle(),
                                LeaseReleaseRequest.of(policy.getMissingHandlerRetryDelayMillis())));
            } catch (Exception writeEx) {
                log.error("Lease worker release failed. taskId={}", grant.getTaskId(), writeEx);
            }
            return;
        }
        handleFailure(grant, ex, LeaseTaskFailureReason.MISSING_HANDLER);
    }

    /**
     * 处理任务执行失败的情况
     *
     * @param grant 当前任务租约
     * @param ex    捕获到的异常
     */
    private void handleFailure(LeaseGrant grant, Exception ex) {
        handleFailure(grant, ex, LeaseTaskFailureReason.HANDLER_EXCEPTION);
    }

    private void handleFailure(LeaseGrant grant, Exception ex, LeaseTaskFailureReason reason) {
        log.error("Lease worker handle failed. taskId={}, queue={}, taskType={}",
                grant.getTaskId(), grant.getQueue(), grant.getTaskType(), ex);
        try {
            handleWriteResult("close", grant,
                    runtimeClient.close(grant.getHandle(), LeaseCloseRequest.failed(reason, String.valueOf(ex))));
        } catch (Exception writeEx) {
            log.error("Lease worker write-back failed. taskId={}", grant.getTaskId(), writeEx);
        }
    }

    private HeartbeatTask createHeartbeatTask(LeaseGrant grant) {
        if (!policy.isHeartbeatEnabled()) {
            return null;
        }
        return new HeartbeatTask(runtimeClient, heartbeatExecutor, grant,
                policy.getHeartbeatIntervalMillis(), policy.getLeaseMillis(), policy.getWorkerId());
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

    private void handleWriteResult(String operation, LeaseGrant grant, LeaseRuntimeResult result) {
        if (result == null || result == LeaseRuntimeResult.APPLIED) {
            return;
        }
        if (result == LeaseRuntimeResult.LEASE_LOST) {
            log.warn("Lease worker {} ignored because lease was lost. taskId={}, workerId={}",
                    operation, grant.getTaskId(), policy.getWorkerId());
            return;
        }
        if (result == LeaseRuntimeResult.TASK_NOT_FOUND) {
            log.info("Lease worker {} ignored because task was not found. taskId={}, workerId={}",
                    operation, grant.getTaskId(), policy.getWorkerId());
            return;
        }
        log.warn("Lease worker {} ignored. taskId={}, workerId={}, result={}",
                operation, grant.getTaskId(), policy.getWorkerId(), result);
    }

    private boolean waitForWorker(Thread threadToJoin, long timeoutMillis) {
        if (threadToJoin == null || threadToJoin == Thread.currentThread()) {
            return true;
        }
        long deadline = timeoutMillis > 0L ? System.currentTimeMillis() + timeoutMillis : 0L;
        try {
            if (timeoutMillis > 0L) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining > 0L) {
                    threadToJoin.join(remaining);
                }
                return !threadToJoin.isAlive();
            }
            threadToJoin.join();
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return !threadToJoin.isAlive();
        }
    }

    private void waitForHeartbeatExecutor(long timeoutMillis) {
        try {
            if (timeoutMillis > 0L) {
                if (!heartbeatExecutor.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS)) {
                    heartbeatExecutor.shutdownNow();
                }
            } else {
                while (!heartbeatExecutor.awaitTermination(1L, TimeUnit.SECONDS)) {
                    // wait until the current task completes and the heartbeat loop exits cleanly
                }
            }
        } catch (InterruptedException e) {
            heartbeatExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    private static class HeartbeatTask implements Runnable {
        private final LeaseRuntimeClient runtimeClient;
        private final ScheduledExecutorService executor;
        private final LeaseGrant grant;
        private final long intervalMillis;
        private final long leaseMillis;
        private final String workerId;
        private final AtomicBoolean heartbeating = new AtomicBoolean(false);
        private volatile java.util.concurrent.ScheduledFuture<?> future;

        private void start() {
            future = executor.scheduleAtFixedRate(this, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
        }

        private void stop() {
            if (future != null) {
                future.cancel(true);
            }
        }

        private void requestNow() {
            try {
                executor.execute(this);
            } catch (RejectedExecutionException ignored) {
                // worker is shutting down; skip late manual heartbeat requests
            }
        }

        @Override
        public void run() {
            if (!heartbeating.compareAndSet(false, true)) {
                return;
            }
            try {
                LeaseRuntimeResult result = runtimeClient.heartbeat(grant.getHandle(), leaseMillis);
                if (result != LeaseRuntimeResult.APPLIED) {
                    log.warn("Lease heartbeat not applied. taskId={}, workerId={}, result={}",
                            grant.getTaskId(), workerId, result);
                }
            } catch (Exception ex) {
                log.warn("Lease heartbeat failed. taskId={}, workerId={}", grant.getTaskId(), workerId, ex);
            } finally {
                heartbeating.set(false);
            }
        }
    }
}
