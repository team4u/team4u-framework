package com.team4u.framework.lease.runtime;

import com.team4u.framework.base.util.DurationUtil;
import com.team4u.framework.base.util.ThreadUtil;
import com.team4u.framework.lease.api.TaskContext;
import com.team4u.framework.lease.api.TaskHandler;
import com.team4u.framework.lease.api.TaskResult;
import com.team4u.framework.lease.api.TaskQueue;
import com.team4u.framework.lease.api.TaskSnapshot;
import com.team4u.framework.lease.spi.AcquireCommand;
import com.team4u.framework.lease.spi.LeaseBackend;
import com.team4u.framework.lease.spi.LeaseCompletion;
import com.team4u.framework.lease.spi.LeaseGrant;
import com.team4u.framework.lease.spi.LeaseRetry;
import com.team4u.framework.lease.spi.RuntimeResult;
import com.team4u.framework.lease.spi.TaskSubscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
public final class TaskWorker implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TaskWorker.class);

    private static final long DEFAULT_LEASE_MILLIS = 30_000L;
    private static final long DEFAULT_POLL_INTERVAL_MILLIS = 250L;

    private final LeaseBackend backend;
    private final TaskSubscription subscription;
    private final Map<String, TaskHandler> handlers;
    private final String workerId;
    private final long leaseMillis;
    private final long pollIntervalMillis;
    private final boolean heartbeatEnabled;
    private final long heartbeatIntervalMillis;
    private final String threadName;
    private final ScheduledExecutorService heartbeatExecutor;

    private volatile boolean running;
    private volatile boolean shutdown;
    private Thread workerThread;
    private final AtomicBoolean processingTask = new AtomicBoolean(false);
    private final AtomicBoolean heartbeatExecutorShutdown = new AtomicBoolean(false);
    private TaskWorker(Builder builder) {
        this.backend = builder.backend;
        this.handlers = Collections.unmodifiableMap(new LinkedHashMap<String, TaskHandler>(
                builder.handlers));
        this.subscription = TaskSubscription.of(builder.queueName, builder.handlers.keySet());
        this.workerId = builder.workerId;
        this.leaseMillis = builder.leaseMillis;
        this.pollIntervalMillis = builder.pollIntervalMillis;
        this.heartbeatEnabled = builder.heartbeatEnabled;
        this.heartbeatIntervalMillis = builder.resolvedHeartbeatIntervalMillis();
        this.threadName = builder.threadName == null ? "task-worker-" + builder.workerId
                : builder.threadName;
        this.heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, threadName + "-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
    }

    public synchronized TaskWorker start() {
        if (shutdown) {
            throw new IllegalStateException("TaskWorker cannot be restarted after shutdown");
        }
        if (running) {
            return this;
        }
        running = true;
        workerThread = new Thread(this::run, threadName);
        workerThread.setDaemon(true);
        workerThread.start();
        return this;
    }

    public void shutdown() {
        if (!shutdownGracefully(Duration.ofMillis(leaseMillis))) {
            shutdownNow();
        }
    }

    public boolean shutdownGracefully(Duration timeout) {
        long timeoutMillis = DurationUtil.requireExactMillis(timeout, "timeout");
        Thread threadToJoin;
        synchronized (this) {
            shutdown = true;
            running = false;
            threadToJoin = workerThread;
            if (threadToJoin != null && !processingTask.get()) {
                threadToJoin.interrupt();
            }
        }

        boolean stopped = waitForWorker(threadToJoin, timeoutMillis);
        if (stopped) {
            shutdownHeartbeatExecutor();
            waitForHeartbeatExecutor(timeoutMillis);
        }
        return stopped;
    }

    public synchronized void shutdownNow() {
        shutdown = true;
        running = false;
        if (workerThread != null) {
            workerThread.interrupt();
        }
        heartbeatExecutor.shutdownNow();
        heartbeatExecutorShutdown.set(true);
    }

    @Override
    public void close() {
        shutdown();
    }

    private void run() {
        try {
            while (running && !Thread.currentThread().isInterrupted()) {
                LeaseGrant grant = acquireNextGrant();
                if (grant == null) {
                    continue;
                }
                processGrant(grant);
            }
        } finally {
            shutdownHeartbeatExecutor();
            log.info("Task worker stopped. workerId={}", workerId);
        }
    }


    private void processGrant(LeaseGrant grant) {
        processingTask.set(true);
        boolean infrastructureFailure = false;
        try {
            HeartbeatTask heartbeatTask = createHeartbeatTask(grant);
            if (heartbeatTask != null && !heartbeatTask.start()) {
                return;
            }
            try {
                TaskResult result = executeHandler(grant);
                if (result == null) {
                    throw new IllegalStateException("TaskHandler returned null");
                }
                writeBack(grant, result);
            } catch (TaskInfrastructureException ex) {
                log.error("Task worker abandoning lease after infrastructure failure; "
                        + "lease will expire and fencing will govern recovery. taskId={}, workerId={}",
                        grant.getHandle().getTaskId(), workerId, ex);
                infrastructureFailure = true;
            } catch (Exception ex) {
                closeAsFailure(grant, ex);
            } finally {
                if (heartbeatTask != null) {
                    heartbeatTask.stop();
                }
            }
        } finally {
            processingTask.set(false);
        }
        if (shutdown) {
            Thread.currentThread().interrupt();
        } else if (infrastructureFailure) {
            sleepQuietly();
        }
    }

    private TaskResult executeHandler(LeaseGrant grant) throws Exception {
        TaskSnapshot snapshot = grant.getSnapshot();
        TaskHandler handler = handlers.get(snapshot.getType());
        if (handler == null) {
            throw new IllegalStateException("No TaskHandler for type " + snapshot.getType());
        }
        return handler.handle(new GrantTaskContext(snapshot));
    }

    private void writeBack(LeaseGrant grant, TaskResult result) {
        if (result == null) {
            closeAsFailure(grant, new IllegalStateException("TaskHandler returned null"));
            return;
        }

        try {
            if (result.isRetry()) {
                RuntimeResult runtimeResult = backend.release(grant.getHandle(), LeaseRetry.of(
                        result.getRetryDelay().toMillis(),
                        result.getPayload(),
                        result.getErrorMessage(),
                        attributes(result)));
                logWriteResult("release", grant, runtimeResult);
                return;
            }

            LeaseCompletion completion = toCompletion(result);
            RuntimeResult runtimeResult = backend.close(grant.getHandle(), completion);
            logWriteResult("close", grant, runtimeResult);
        } catch (Exception writeEx) {
            log.error("Task worker write-back failed. taskId={}, workerId={}",
                    grant.getHandle().getTaskId(), workerId, writeEx);
        }
    }
    private static LeaseCompletion toCompletion(TaskResult result) {
        if (result.isSuccess()) {
            return LeaseCompletion.succeeded(result.getPayload(), attributes(result));
        }
        if (result.isFailure()) {
            return LeaseCompletion.failed(result.getErrorMessage(), result.getPayload(),
                    attributes(result));
        }
        return LeaseCompletion.cancelled(result.getErrorMessage(), result.getPayload(),
                attributes(result));
    }

    private static java.util.Map<String, String> attributes(TaskResult result) {
        return result.hasAttributes() ? result.getAttributes() : null;
    }

    private void closeAsFailure(LeaseGrant grant, Exception ex) {
        log.error("Task worker handler failed. taskId={}, workerId={}",
                grant.getHandle().getTaskId(), workerId, ex);
        try {
            RuntimeResult result = backend.close(grant.getHandle(), LeaseCompletion.failed(
                    String.valueOf(ex), null, null));
            logWriteResult("close", grant, result);
        } catch (Exception writeEx) {
            log.error("Task worker failure write-back failed. taskId={}",
                    grant.getHandle().getTaskId(), writeEx);
        }
    }

    private LeaseGrant acquireNextGrant() {
        try {
            LeaseGrant grant = backend.acquire(AcquireCommand.of(subscription, workerId,
                    leaseMillis));
            if (grant == null) {
                sleepQuietly();
            }
            return grant;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception ex) {
            log.error("Task worker acquire failed. workerId={}", workerId, ex);
            sleepQuietly();
            return null;
        }
    }

    private HeartbeatTask createHeartbeatTask(LeaseGrant grant) {
        if (!heartbeatEnabled) {
            return null;
        }
        return new HeartbeatTask(grant);
    }
    private void sleepQuietly() {
        // 中断优先于时长：被中断意味着关闭信号，返回 false 后由外层循环的
        // running/中断标志检测退出，避免忙转
        ThreadUtil.sleepQuietly(pollIntervalMillis);
    }

    private void logWriteResult(String operation, LeaseGrant grant, RuntimeResult result) {
        if (result == RuntimeResult.APPLIED) {
            return;
        }
        if (result == null) {
            log.error("Task worker {} returned null. taskId={}", operation,
                    grant.getHandle().getTaskId());
            return;
        }
        log.warn("Task worker {} ignored. taskId={}, workerId={}, result={}",
                operation, grant.getHandle().getTaskId(), workerId, result);
    }

    private void shutdownHeartbeatExecutor() {
        if (heartbeatExecutorShutdown.compareAndSet(false, true)) {
            heartbeatExecutor.shutdown();
        }
    }

    private void waitForHeartbeatExecutor(long timeoutMillis) {
        try {
            if (timeoutMillis > 0L) {
                if (!heartbeatExecutor.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS)) {
                    heartbeatExecutor.shutdownNow();
                }
            } else if (!heartbeatExecutor.isTerminated()) {
                heartbeatExecutor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            heartbeatExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private boolean waitForWorker(Thread thread, long timeoutMillis) {
        if (thread == null || thread == Thread.currentThread()) {
            return true;
        }
        try {
            if (timeoutMillis > 0L) {
                long deadline = System.currentTimeMillis() + timeoutMillis;
                long remaining = deadline - System.currentTimeMillis();
                if (remaining > 0L) {
                    thread.join(remaining);
                }
                return !thread.isAlive();
            }
            return !thread.isAlive();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return !thread.isAlive();
        }
    }

    public static final class Builder {
        private final LeaseBackend backend;
        private final String queueName;
        private final Map<String, TaskHandler> handlers = new LinkedHashMap<String, TaskHandler>();
        private String workerId;
        private long leaseMillis = DEFAULT_LEASE_MILLIS;
        private long pollIntervalMillis = DEFAULT_POLL_INTERVAL_MILLIS;
        private boolean heartbeatEnabled = true;
        private Long heartbeatIntervalMillis;
        private String threadName;

        Builder(TaskQueue queue, LeaseBackend backend) {
            this.backend = backend;
            this.queueName = queue.name();
        }

        public Builder handle(String type, TaskHandler handler) {
            requireText(type, "type");
            if (handler == null) {
                throw new IllegalArgumentException("handler must not be null");
            }
            if (handlers.containsKey(type)) {
                throw new IllegalArgumentException("handler already exists for type " + type);
            }
            handlers.put(type, handler);
            return this;
        }

        public Builder workerId(String workerId) {
            if (workerId == null) {
                throw new IllegalArgumentException("workerId must not be null");
            }
            this.workerId = workerId;
            return this;
        }

        public Builder lease(Duration lease) {
            this.leaseMillis = positiveDuration(lease, "lease");
            return this;
        }

        public Builder pollInterval(Duration pollInterval) {
            this.pollIntervalMillis = DurationUtil.requireNonNegativeMillis(pollInterval,
                    "pollInterval");
            return this;
        }

        public Builder heartbeatEnabled(boolean heartbeatEnabled) {
            this.heartbeatEnabled = heartbeatEnabled;
            return this;
        }

        public Builder heartbeatInterval(Duration heartbeatInterval) {
            this.heartbeatIntervalMillis = Long.valueOf(positiveDuration(heartbeatInterval,
                    "heartbeatInterval"));
            return this;
        }

        public Builder threadName(String threadName) {
            this.threadName = threadName;
            return this;
        }

        public TaskWorker build() {
            if (workerId == null) {
                workerId = "worker-" + UUID.randomUUID();
            }
            requireText(workerId, "workerId");
            if (handlers.isEmpty()) {
                throw new IllegalArgumentException("handlers must not be empty");
            }
            if (heartbeatEnabled) {
                long interval = resolvedHeartbeatIntervalMillis();
                if (interval <= 0L || interval >= leaseMillis) {
                    throw new IllegalArgumentException(
                            "heartbeatInterval must be positive and less than lease");
                }
            }
            Set<String> types = new LinkedHashSet<String>();
            for (String type : handlers.keySet()) {
                types.add(type);
            }
            TaskSubscription.of(queueName, types);
            return new TaskWorker(this);
        }

        private long resolvedHeartbeatIntervalMillis() {
            if (heartbeatIntervalMillis != null) {
                return heartbeatIntervalMillis.longValue();
            }
            return heartbeatEnabled ? defaultHeartbeatInterval(leaseMillis) : 0L;
        }

        private static long defaultHeartbeatInterval(long leaseMillis) {
            return leaseMillis / 3L;
        }

        private static long positiveDuration(Duration duration, String name) {
            return DurationUtil.requirePositiveMillis(duration, name);
        }

        private static void requireText(String value, String name) {
            if (value == null || value.trim().isEmpty()) {
                throw new IllegalArgumentException(name + " must not be blank");
            }
        }
    }

    /**
     * 单个租约持有期的心跳任务：持锁期间在独立调度线程上周期性续约
     * <p>
     * 与 base 的 ScheduledHeartbeat 相比，本类刻意保留私有实现，原因：
     * </p>
     * <ul>
     *     <li><b>生命周期与 worker 关闭联动</b>：心跳复用 worker 级单线程调度器，
     *     worker 关闭时调度器一并终止，此后调度被拒即视为「持锁期被关闭切断」，
     *     立即放弃执行让租约自然过期——而 ScheduledHeartbeat 每实例独占调度器，
     *     无法感知 worker 关闭时序；</li>
     *     <li><b>丢失后不回调仅停止</b>：worker 对 LEASE_LOST 的响应是放弃当次
     *     write-back（租约已被接管，写入会失败），不需要 onLost 回调介入。
     *     持有者仍会继续跑完 handler 并尝试 close，由条件 UPDATE 的 fencing 拒绝。</li>
     * </ul>
     * 线程模型：daemon 单线程 ScheduledExecutorService（命名 {@code <threadName>-heartbeat}），
     * stop 时取消后续调度，线程随 worker 关闭回收。
     */
    private final class HeartbeatTask implements Runnable {
        private final LeaseGrant grant;
        private final AtomicBoolean heartbeating = new AtomicBoolean(false);
        private volatile ScheduledFuture<?> future;

        private HeartbeatTask(LeaseGrant grant) {
            this.grant = grant;
        }

        private boolean start() {
            try {
                future = heartbeatExecutor.scheduleAtFixedRate(this, heartbeatIntervalMillis,
                        heartbeatIntervalMillis, TimeUnit.MILLISECONDS);
                return true;
            } catch (java.util.concurrent.RejectedExecutionException ex) {
                log.warn("Task heartbeat scheduling rejected; abandoning execution and letting lease expire. taskId={}, workerId={}",
                        grant.getHandle().getTaskId(), workerId, ex);
                return false;
            }
        }

        private void stop() {
            if (future != null) {
                future.cancel(true);
            }
        }

        @Override
        public void run() {
            if (!heartbeating.compareAndSet(false, true)) {
                return;
            }
            try {
                RuntimeResult result = backend.heartbeat(grant.getHandle(), leaseMillis);
                if (result != RuntimeResult.APPLIED) {
                    log.warn("Task heartbeat not applied. taskId={}, workerId={}, result={}",
                            grant.getHandle().getTaskId(), workerId, result);
                    stop();
                }
            } catch (Exception ex) {
                log.warn("Task heartbeat failed. taskId={}, workerId={}",
                        grant.getHandle().getTaskId(), workerId, ex);
            } finally {
                heartbeating.set(false);
            }
        }
    }

    private static final class GrantTaskContext implements TaskContext {
        private final TaskSnapshot snapshot;

        private GrantTaskContext(TaskSnapshot snapshot) {
            this.snapshot = snapshot;
        }

        @Override
        public String getTaskId() {
            return snapshot.getTaskId();
        }

        @Override
        public String getQueue() {
            return snapshot.getQueue();
        }

        @Override
        public String getType() {
            return snapshot.getType();
        }

        @Override
        public String getPayload() {
            return snapshot.getPayload();
        }

        @Override
        public int getAttemptCount() {
            return snapshot.getAttemptCount();
        }

        @Override
        public Map<String, String> getAttributes() {
            return snapshot.getAttributes();
        }

        @Override
        public Instant getCreatedAt() {
            return snapshot.getCreatedAt();
        }

        @Override
        public Instant getVisibleAt() {
            return snapshot.getVisibleAt();
        }
    }
}
