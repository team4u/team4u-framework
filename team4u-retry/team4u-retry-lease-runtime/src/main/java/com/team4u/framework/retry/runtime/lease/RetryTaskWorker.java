package com.team4u.framework.retry.runtime.lease;

import com.team4u.framework.lease.api.TaskHandler;
import com.team4u.framework.lease.api.TaskQueue;
import com.team4u.framework.lease.runtime.TaskWorker;
import com.team4u.framework.retry.managed.recovery.RecoveryHandler;
import com.team4u.framework.retry.managed.recovery.RecoveryHandlerRegistry;
import com.team4u.framework.retry.managed.recovery.StringRecoveryHandler;
import com.team4u.framework.retry.managed.store.serialize.RetryRecordSerializer;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Background worker for retry tasks in one task queue.
 */
public class RetryTaskWorker implements AutoCloseable {

    private final TaskQueue queue;
    private final RetryRecordSerializer serializer;
    private final RecoveryHandlerRegistry registry;
    private final String workerId;
    private final Duration lease;
    private final Duration pollInterval;
    private final boolean heartbeatEnabled;
    private final Duration heartbeatInterval;
    private final String threadName;
    private final Map<String, TaskHandler> handlers = new LinkedHashMap<String, TaskHandler>();

    private boolean started;
    private boolean shutdown;
    private TaskWorker delegate;

    public RetryTaskWorker(TaskQueue queue, RecoveryHandlerRegistry registry) {
        this(queue, registry, LeaseRetryRecordSerializer.INSTANCE);
    }

    public RetryTaskWorker(
            TaskQueue queue,
            RecoveryHandlerRegistry registry,
            RetryRecordSerializer serializer) {
        this(queue, registry, null, null, null, true, null, null, serializer);
    }

    RetryTaskWorker(
            TaskQueue queue,
            RecoveryHandlerRegistry registry,
            String workerId,
            Duration lease,
            Duration pollInterval,
            boolean heartbeatEnabled,
            Duration heartbeatInterval,
            String threadName,
            RetryRecordSerializer serializer) {
        if (queue == null) {
            throw new IllegalArgumentException("TaskQueue must not be null");
        }
        if (registry == null) {
            throw new IllegalArgumentException("RecoveryHandlerRegistry must not be null");
        }
        if (serializer == null) {
            throw new IllegalArgumentException("RetryRecordSerializer must not be null");
        }
        this.queue = queue;
        this.serializer = serializer;
        this.workerId = workerId;
        this.lease = lease;
        this.pollInterval = pollInterval;
        this.heartbeatEnabled = heartbeatEnabled;
        this.heartbeatInterval = heartbeatInterval;
        this.threadName = threadName;
        this.registry = registry;
    }

    public String queueName() {
        return queue.name();
    }

    public RetryRecordSerializer serializer() {
        return serializer;
    }

    public synchronized boolean isStarted() {
        return started;
    }

    public synchronized RetryTaskWorker start() {
        if (started) {
            return this;
        }
        if (shutdown) {
            throw new IllegalStateException(
                    "RetryTaskWorker cannot be restarted after shutdown");
        }
        // Snapshot registry contents under this worker's lock so registrations made before
        // start are visible, while later registry mutations cannot affect a started worker.
        registerRegistryHandlers(registry);
        if (handlers.isEmpty()) {
            throw new IllegalStateException("RetryTaskWorker requires at least one handler");
        }

        TaskWorker.Builder builder = queue.worker();
        for (Map.Entry<String, TaskHandler> entry : handlers.entrySet()) {
            builder.handle(entry.getKey(), entry.getValue());
        }
        if (workerId != null) {
            builder.workerId(workerId);
        }
        if (lease != null) {
            builder.lease(lease);
        }
        if (pollInterval != null) {
            builder.pollInterval(pollInterval);
        }
        builder.heartbeatEnabled(heartbeatEnabled);
        if (heartbeatInterval != null) {
            builder.heartbeatInterval(heartbeatInterval);
        }
        if (threadName != null) {
            builder.threadName(threadName);
        }
        delegate = builder.build();
        try {
            delegate.start();
            started = true;
            return this;
        } catch (RuntimeException ex) {
            delegate = null;
            throw ex;
        }
    }

    public synchronized void shutdown() {
        shutdownDelegate();
        started = false;
        shutdown = true;
    }

    public synchronized boolean shutdownGracefully(Duration timeout) {
        TaskWorker worker = delegate;
        boolean stopped = worker == null || worker.shutdownGracefully(timeout);
        started = false;
        shutdown = true;
        return stopped;
    }

    public synchronized void shutdownNow() {
        TaskWorker worker = delegate;
        if (worker != null) {
            worker.shutdownNow();
        }
        started = false;
        shutdown = true;
    }

    /**
     * Registers a handler in this worker's start snapshot. Registration is local to this worker;
     * it never mutates the shared recovery registry.
     */
    public synchronized void register(StringRecoveryHandler handler) {
        if (handler == null) {
            throw new IllegalArgumentException("StringRecoveryHandler must not be null");
        }
        if (started || shutdown) {
            throw new IllegalStateException(
                    "Cannot register handler after RetryTaskWorker starts");
        }
        registerHandler(handler);
    }

    @Override
    public synchronized void close() {
        shutdown();
    }

    private void shutdownDelegate() {
        TaskWorker worker = delegate;
        if (worker != null) {
            worker.shutdown();
        }
        delegate = null;
    }

    private void registerRegistryHandlers(RecoveryHandlerRegistry registry) {
        for (RecoveryHandler<?> handler : registry.getPolicies()) {
            registerHandler(handler);
        }
    }

    private void registerHandler(RecoveryHandler<?> handler) {
        if (!(handler instanceof StringRecoveryHandler)) {
            throw new IllegalArgumentException(
                    "RetryTaskWorker requires StringRecoveryHandler. handler="
                            + handler.getClass().getName());
        }
        String type = handler.taskName();
        if (type == null || type.trim().isEmpty()) {
            throw new IllegalArgumentException("Recovery handler taskName must not be blank");
        }
        if (handlers.containsKey(type)) {
            throw new IllegalArgumentException("handler already exists for type " + type);
        }
        handlers.put(type,
                new RecoveryHandlerTaskHandlerAdapter((StringRecoveryHandler) handler, serializer));
    }
}
