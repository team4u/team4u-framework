package com.team4u.framework.retry.worker;

import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import com.team4u.framework.retry.recovery.RecoveryHandler;
import com.team4u.framework.retry.recovery.RecoveryHandlerRegistry;

/**
 * 后台重试 Worker，负责消费 backend 中到期任务并恢复执行。
 */
public class RetryWorker implements Runnable, AutoCloseable {

    private static final Log log = LogFactory.get();

    private final WorkerReadableRetryBackend backend;
    private final RecoveryHandlerRegistry registry;

    private volatile boolean running;
    private Thread workerThread;

    public RetryWorker(WorkerReadableRetryBackend backend) {
        this(backend, RecoveryHandlerRegistry.global());
    }

    public RetryWorker(WorkerReadableRetryBackend backend, RecoveryHandlerRegistry registry) {
        this.backend = backend;
        this.registry = registry;
    }

    public synchronized void start() {
        start("retry-worker");
    }

    public synchronized void start(String threadName) {
        if (running) {
            return;
        }
        running = true;
        workerThread = new Thread(this, threadName == null ? "retry-worker" : threadName);
        workerThread.setDaemon(true);
        workerThread.start();
    }

    public synchronized void shutdown() {
        running = false;
        if (workerThread != null) {
            workerThread.interrupt();
        }
    }

    @Override
    public void close() {
        shutdown();
    }

    @Override
    public void run() {
        while (running && !Thread.currentThread().isInterrupted()) {
            RetryTaskRecord task;
            try {
                task = backend.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }

            try {
                RecoveryHandler handler = registry.get(task.getTaskType())
                        .orElseThrow(() -> new IllegalStateException(
                                "RecoveryHandler not found. taskType=" + task.getTaskType()));
                handler.recover(task.getPayload());
                backend.completeIntent(task.getIntentId());
            } catch (Throwable ex) {
                log.error("Retry worker recover failed. intentId={}, taskType={}",
                        task.getIntentId(), task.getTaskType(), ex);
                try {
                    backend.markTerminalFailure(task.getIntentId(), ex);
                } catch (Throwable markEx) {
                    log.error("Retry worker markTerminalFailure failed. intentId={}", task.getIntentId(), markEx);
                }
            }
        }

        log.info("Retry worker stopped.");
    }
}
