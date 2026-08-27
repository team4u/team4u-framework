package com.team4u.framework.retry.runtime.lease;

import com.team4u.framework.lease.Leases;
import com.team4u.framework.lease.api.TaskQueue;
import com.team4u.framework.lease.spi.LeaseBackend;
import com.team4u.framework.retry.api.RetryPolicy;
import com.team4u.framework.retry.managed.store.serialize.RetryRecordSerializer;
import com.team4u.framework.retry.managed.client.DefaultManagedRetryClient;
import com.team4u.framework.retry.managed.client.ManagedRetryClient;
import com.team4u.framework.retry.managed.recovery.RecoveryHandlerRegistry;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.time.Duration;

/**
 * Managed retry runtime for one lease task queue.
 */
public class ManagedRetryRuntime implements AutoCloseable {

    private final ManagedRetryClient client;
    private final RecoveryHandlerRegistry registry;
    private final RetryTaskWorker worker;
    private final LeaseDurableRetryStore store;
    private volatile boolean started;

    ManagedRetryRuntime(
            ManagedRetryClient client,
            RecoveryHandlerRegistry registry,
            RetryTaskWorker worker,
            LeaseDurableRetryStore store) {
        this.client = client;
        this.registry = registry;
        this.store = store;
        this.worker = worker;
    }

    public static Builder lease(LeaseBackend backend) {
        return new Builder(backend);
    }

    public ManagedRetryClient client() {
        return client;
    }

    public RecoveryHandlerRegistry registry() {
        return registry;
    }

    public RetryTaskWorker worker() {
        return worker;
    }

    public String queueName() {
        return worker.queueName();
    }

    public long foregroundRecoveryTimeoutMillis() {
        return store.foregroundRecoveryTimeoutMillis();
    }

    public synchronized void start() {
        if (started) {
            return;
        }
        worker.start();
        started = true;
    }

    public synchronized void shutdown() {
        worker.shutdown();
        started = false;
    }

    @Override
    public void close() {
        shutdown();
    }

    @Setter
    @Accessors(fluent = true, chain = true)
    public static class Builder {

        private final LeaseBackend backend;
        private String queueName = RetryTaskQueues.DEFAULT_RECOVERY_QUEUE;
        private RecoveryHandlerRegistry registry;
        private RetryPolicy defaultPolicy;
        private boolean autoScanRecoveryHandlers = true;
        private String workerId;
        private Duration lease = Duration.ofSeconds(30);
        private Duration pollInterval = Duration.ofMillis(250);
        private boolean heartbeatEnabled = true;
        private Duration heartbeatInterval;
        private String threadName;
        private RetryRecordSerializer serializer = LeaseRetryRecordSerializer.INSTANCE;
        private Duration foregroundRecoveryTimeout = Duration.ofMinutes(5L);
        Builder(LeaseBackend backend) {
            if (backend == null) {
                throw new IllegalArgumentException("LeaseBackend must not be null");
            }
            this.backend = backend;
        }

        public Builder queueName(String queueName) {
            if (queueName == null || queueName.trim().isEmpty()) {
                throw new IllegalArgumentException("queueName must not be blank");
            }
            this.queueName = queueName;
            return this;
        }

        public ManagedRetryRuntime build() {
            RecoveryHandlerRegistry resolvedRegistry =
                    registry == null ? new RecoveryHandlerRegistry() : registry;
            if (autoScanRecoveryHandlers) {
                resolvedRegistry.autoScan();
            }
            TaskQueue queue = Leases.queue(backend, queueName);
            RetryRecordSerializer resolvedSerializer =
                    serializer == null ? LeaseRetryRecordSerializer.INSTANCE : serializer;
            LeaseDurableRetryStore store = new LeaseDurableRetryStore(
                    queue, resolvedSerializer, foregroundRecoveryTimeout);
            ManagedRetryClient client = DefaultManagedRetryClient.builder()
                    .store(store)
                    .dispatcher(store)
                    .defaultPolicy(defaultPolicy)
                    .build();
            // Snapshot the registry at worker start: adapters retain the current handlers and
            // later RetryTaskWorker.register calls only affect this local worker.
            RetryTaskWorker worker = new RetryTaskWorker(
                    queue,
                    resolvedRegistry,
                    workerId,
                    lease,
                    pollInterval,
                    heartbeatEnabled,
                    heartbeatInterval,
                    threadName,
                    resolvedSerializer);
            return new ManagedRetryRuntime(client, resolvedRegistry, worker, store);
        }

        public ManagedRetryRuntime start() {
            ManagedRetryRuntime runtime = build();
            runtime.start();
            return runtime;
        }
    }
}
