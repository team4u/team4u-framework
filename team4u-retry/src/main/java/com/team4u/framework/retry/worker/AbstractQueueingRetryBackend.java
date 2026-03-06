package com.team4u.framework.retry.worker;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;

/**
 * 提供基于内存索引 + DelayQueue 的通用后端能力。
 */
abstract class AbstractQueueingRetryBackend implements WorkerReadableRetryBackend {

    private final long pendingRecoverAfterMillis;
    private final Map<String, RetryTaskRecord> records = new ConcurrentHashMap<String, RetryTaskRecord>();
    private final DelayQueue<DelayedRef> queue = new DelayQueue<DelayedRef>();

    protected AbstractQueueingRetryBackend(long pendingRecoverAfterMillis) {
        this.pendingRecoverAfterMillis = pendingRecoverAfterMillis;
    }

    @Override
    public synchronized String saveIntent(String taskType, String payload) {
        long now = System.currentTimeMillis();
        String intentId = nextIntentId();
        RetryTaskRecord record = new RetryTaskRecord(
                intentId,
                taskType,
                payload,
                now,
                now + pendingRecoverAfterMillis,
                RetryTaskRecord.PENDING,
                null
        );
        records.put(intentId, record);
        queue.offer(new DelayedRef(intentId, record.getExecuteAtMillis()));
        afterStateChange();
        return intentId;
    }

    @Override
    public synchronized void completeIntent(String intentId) {
        records.remove(intentId);
        afterStateChange();
    }

    @Override
    public synchronized void markTerminalFailure(String intentId, Throwable cause) {
        RetryTaskRecord current = records.get(intentId);
        if (current == null) {
            return;
        }
        current.setStatus(RetryTaskRecord.TERMINAL);
        current.setLastError(cause == null ? null : String.valueOf(cause));
        records.put(intentId, current);
        afterStateChange();
    }

    @Override
    public synchronized void submitForDelay(String intentId, String taskType, String payload, long delay) {
        long now = System.currentTimeMillis();
        RetryTaskRecord old = records.get(intentId);
        long createdAt = old == null ? now : old.getCreatedAt();
        RetryTaskRecord record = new RetryTaskRecord(
                intentId,
                taskType,
                payload,
                createdAt,
                now + Math.max(0L, delay),
                RetryTaskRecord.QUEUED,
                null
        );
        records.put(intentId, record);
        queue.offer(new DelayedRef(intentId, record.getExecuteAtMillis()));
        afterStateChange();
    }

    @Override
    public RetryTaskRecord take() throws InterruptedException {
        while (true) {
            DelayedRef ref = queue.take();
            RetryTaskRecord current = records.get(ref.intentId);
            if (current == null) {
                continue;
            }
            if (RetryTaskRecord.TERMINAL.equals(current.getStatus())) {
                continue;
            }
            if (current.getExecuteAtMillis() != ref.executeAtMillis) {
                continue;
            }
            return current.copy();
        }
    }

    public synchronized Map<String, RetryTaskRecord> snapshot() {
        Map<String, RetryTaskRecord> snapshot = new LinkedHashMap<String, RetryTaskRecord>();
        for (Map.Entry<String, RetryTaskRecord> entry : records.entrySet()) {
            snapshot.put(entry.getKey(), entry.getValue().copy());
        }
        return snapshot;
    }

    protected synchronized void restoreRecords(Collection<RetryTaskRecord> restored) {
        records.clear();
        queue.clear();
        if (restored == null) {
            return;
        }
        for (RetryTaskRecord record : restored) {
            if (record == null || record.getIntentId() == null) {
                continue;
            }
            records.put(record.getIntentId(), record);
            if (!RetryTaskRecord.TERMINAL.equals(record.getStatus())) {
                queue.offer(new DelayedRef(record.getIntentId(), record.getExecuteAtMillis()));
            }
        }
    }

    protected synchronized Collection<RetryTaskRecord> recordCopies() {
        Map<String, RetryTaskRecord> snapshot = snapshot();
        return snapshot.values();
    }

    protected void afterStateChange() {
    }

    protected String nextIntentId() {
        return "intent-" + UUID.randomUUID().toString().replace("-", "");
    }

    private static class DelayedRef implements Delayed {
        private final String intentId;
        private final long executeAtMillis;

        private DelayedRef(String intentId, long executeAtMillis) {
            this.intentId = intentId;
            this.executeAtMillis = executeAtMillis;
        }

        @Override
        public long getDelay(TimeUnit unit) {
            long diff = executeAtMillis - System.currentTimeMillis();
            return unit.convert(diff, TimeUnit.MILLISECONDS);
        }

        @Override
        public int compareTo(Delayed other) {
            DelayedRef o = (DelayedRef) other;
            return Long.compare(this.executeAtMillis, o.executeAtMillis);
        }
    }
}
