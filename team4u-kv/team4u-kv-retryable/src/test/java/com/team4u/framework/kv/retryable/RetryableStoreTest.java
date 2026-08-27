package com.team4u.framework.kv.retryable;

import com.team4u.framework.kv.KvRecord;
import com.team4u.framework.kv.KvStore;
import com.team4u.framework.kv.KvStoreException;
import com.team4u.framework.kv.PutMode;
import com.team4u.framework.kv.SpaceKey;
import com.team4u.framework.retry.api.RetryPolicy;
import com.team4u.framework.retry.common.backoff.Backoffs;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class RetryableStoreTest {

    @Test
    public void retriesOnInfrastructureExceptionThenSucceeds() {
        FlakyStore flaky = new FlakyStore(2);
        KvStore store = new RetryableStore(flaky, RetryPolicy.builder()
                .maxRetries(3)
                .backoff(Backoffs.fixed(1))
                .retryOn(KvStoreException.class)
                .build());

        assertTrue(store.put(SpaceKey.of("user", "u1"), KvRecord.of("v1"), PutMode.SET));
        assertEquals(3, flaky.attempts.get());
        assertEquals("v1", store.get(SpaceKey.of("user", "u1")).getValue());
        assertEquals(4, flaky.attempts.get());
    }

    @Test
    public void givesUpAfterMaxRetries() {
        FlakyStore flaky = new FlakyStore(Integer.MAX_VALUE);
        KvStore store = new RetryableStore(flaky, RetryPolicy.builder()
                .maxRetries(2)
                .backoff(Backoffs.fixed(1))
                .retryOn(KvStoreException.class)
                .build());

        try {
            store.get(SpaceKey.of("user", "u1"));
            fail("should exhaust retries");
        } catch (KvStoreException expected) {
            assertEquals(3, flaky.attempts.get()); // 首次 + 2 次重试
        }
    }

    @Test
    public void doesNotRetryOtherExceptions() {
        FlakyStore flaky = new FlakyStore(1) {
            @Override
            RuntimeException failure() {
                return new IllegalStateException("business error");
            }
        };
        KvStore store = new RetryableStore(flaky, RetryPolicy.builder()
                .maxRetries(3)
                .backoff(Backoffs.fixed(1))
                .retryOn(KvStoreException.class)
                .build());

        try {
            store.get(SpaceKey.of("user", "u1"));
            fail("should not swallow non-retryable exception");
        } catch (IllegalStateException expected) {
            assertEquals("业务语义失败不重试", 1, flaky.attempts.get());
        }
    }

    @Test
    public void closeCascadesToInner() {
        ClosableFlakyStore inner = new ClosableFlakyStore(0);
        RetryableStore store = new RetryableStore(inner);

        store.close();

        assertTrue("关闭装饰器应级联关闭内层存储", inner.closed);

        // 关闭为尽力而为，重复调用不抛异常
        store.close();
        assertTrue(inner.closed);
    }

    /**
     * 前 failTimes 次抛基础设施异常的存储桩
     */
    static class FlakyStore implements KvStore {

        final AtomicInteger attempts = new AtomicInteger();
        private final int failTimes;

        FlakyStore(int failTimes) {
            this.failTimes = failTimes;
        }

        RuntimeException failure() {
            return new KvStoreException("connection refused");
        }

        private boolean flaky() {
            return attempts.incrementAndGet() <= failTimes;
        }

        @Override
        public KvRecord get(SpaceKey key) {
            if (flaky()) {
                throw failure();
            }
            return KvRecord.of("v1");
        }

        @Override
        public boolean put(SpaceKey key, KvRecord record, PutMode mode) {
            if (flaky()) {
                throw failure();
            }
            return true;
        }

        @Override
        public boolean remove(SpaceKey key) {
            return !flaky();
        }

        @Override
        public boolean expire(SpaceKey key, long ttlMillis) {
            return !flaky();
        }
    }

    /**
     * 记录关闭状态的存储桩
     */
    static class ClosableFlakyStore extends FlakyStore implements AutoCloseable {

        volatile boolean closed;

        ClosableFlakyStore(int failTimes) {
            super(failTimes);
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
