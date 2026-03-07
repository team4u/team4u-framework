package com.team4u.framework.lease;

import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 基于租约协议消费任务的 Worker 实现。
 */
public class LeaseWorker implements Runnable, AutoCloseable {

    private static final Log log = LogFactory.get();

    private final LeaseRuntimeClient runtimeClient;
    private final LeaseTaskHandlerRegistry registry;
    private final LeaseWorkerPolicy policy;
    private final ScheduledExecutorService heartbeatExecutor;
    private final AtomicBoolean processingTask = new AtomicBoolean(false);

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
        shutdownGracefully(0L);
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
            heartbeatExecutor.shutdown();
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
                    if (heartbeatTask != null) {
                        heartbeatTask.start();
                    }
                    handler.handle(toExecutionContext(grant, heartbeatTask));
                    handleWriteResult("ack", grant,
                            runtimeClient.ack(grant.getTaskId(), policy.getWorkerId(), grant.leaseToken()));
                } catch (NonRetryableLeaseException ex) {
                    handleFailure(grant, ex, false);
                } catch (Exception ex) {
                    handleFailure(grant, ex, true);
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
            heartbeatExecutor.shutdown();
            log.info("Lease worker stopped. workerId={}", policy.getWorkerId());
        }
    }

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

    private LeaseExecutionContext toExecutionContext(LeaseGrant grant, HeartbeatTask heartbeatTask) {
        Runnable heartbeatRequester = heartbeatTask == null ? null : heartbeatTask::requestNow;
        return new LeaseExecutionContext(
                grant.getTaskId(),
                grant.getQueue(),
                grant.getTaskType(),
                grant.getPayload(),
                grant.getDeliveryCount(),
                grant.getFailureCount(),
                grant.getAttributes(),
                grant.getCreatedAtMillis(),
                grant.getVisibleAtMillis(),
                grant.getLeaseExpiresAtMillis(),
                heartbeatRequester);
    }

    private void handleMissingHandler(LeaseGrant grant) {
        IllegalStateException ex = new IllegalStateException("LeaseTaskHandler not found. queue="
                + grant.getQueue() + ", taskType=" + grant.getTaskType());
        handleFailure(grant, ex, policy.getMissingHandlerStrategy() == MissingHandlerStrategy.RETRY_LATER);
    }

    private void handleFailure(LeaseGrant grant, Exception ex, boolean allowRetry) {
        log.error("Lease worker handle failed. taskId={}, queue={}, taskType={}",
                grant.getTaskId(), grant.getQueue(), grant.getTaskType(), ex);
        int nextFailureCount = grant.getFailureCount() + 1;
        try {
            if (allowRetry && policy.shouldRetry(nextFailureCount)) {
                handleWriteResult("retry", grant,
                        runtimeClient.retry(grant.getTaskId(), policy.getWorkerId(), grant.leaseToken(),
                                policy.nextDelayMillis(nextFailureCount), ex));
            } else {
                handleWriteResult("fail", grant,
                        runtimeClient.fail(grant.getTaskId(), policy.getWorkerId(), grant.leaseToken(), ex));
            }
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
            run();
        }

        @Override
        public void run() {
            try {
                LeaseRuntimeResult result = runtimeClient.heartbeat(grant.getTaskId(), workerId,
                        grant.leaseToken(), leaseMillis);
                if (result != LeaseRuntimeResult.APPLIED) {
                    log.warn("Lease heartbeat not applied. taskId={}, workerId={}, result={}",
                            grant.getTaskId(), workerId, result);
                }
            } catch (Exception ex) {
                log.warn("Lease heartbeat failed. taskId={}, workerId={}", grant.getTaskId(), workerId, ex);
            }
        }
    }
}
