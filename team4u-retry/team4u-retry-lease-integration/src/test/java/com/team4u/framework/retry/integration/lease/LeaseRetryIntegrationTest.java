package com.team4u.framework.retry.integration.lease;

import com.team4u.framework.lease.memory.InMemoryLeaseBackend;
import com.team4u.framework.lease.runtime.LeaseWorkerPolicy;
import com.team4u.framework.retry.RetryPolicy;
import com.team4u.framework.retry.Retryer;
import com.team4u.framework.retry.backend.RetryTaskSnapshot;
import com.team4u.framework.retry.recovery.RecoveryHandler;
import com.team4u.framework.retry.recovery.RecoveryHandlerRegistry;
import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 重试与租约集成测试
 */
public class LeaseRetryIntegrationTest {

    @Test
    public void testMultiStageRetry() throws Exception {
        InMemoryLeaseBackend leaseBackend = new InMemoryLeaseBackend();
        LeaseRetryBackend retryBackend = new LeaseRetryBackend(leaseBackend);

        // 策略：总共 4 次尝试，前台 2 次，后台继续
        RetryPolicy policy = RetryPolicy.builder()
                .maxAttempts(4)
                .localAttempts(2)
                .build();

        AtomicInteger counter = new AtomicInteger();
        RecoveryHandler handler = new RecoveryHandler() {
            @Override
            public void recover(RetryTaskSnapshot snapshot) {
                counter.incrementAndGet();
                throw new RuntimeException("test failure");
            }

            @Override
            public String key() {
                return "test";
            }
        };

        // 注册处理器
        RetryLeaseWorker worker = new RetryLeaseWorker(
                leaseBackend,
                retryBackend,
                RecoveryHandlerRegistry.global(),
                LeaseWorkerPolicy.builder().build());
        worker.register(handler);
        worker.start();

        Retryer retryer = Retryer.builder()
                .policy(policy)
                .retryBackend(retryBackend)
                .build();

        try {
            retryer.execute("test", context -> {
                RetryTaskSnapshot snapshot = new RetryTaskSnapshot();
                snapshot.setPayload("test-payload");
                return snapshot;
            }, () -> {
                counter.incrementAndGet();
                throw new RuntimeException("foreground failure");
            });
            Assert.fail("Should throw RetryHandoffException");
        } catch (org.junit.ComparisonFailure fail) {
            throw fail;
        } catch (Exception e) {
            // 此时前台已执行 2 次
            Assert.assertEquals(2, counter.get());
        }

        // 等待后台执行，总共应执行 4 次
        long deadline = System.currentTimeMillis() + 5000;
        while (counter.get() < 4 && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }

        Assert.assertEquals(4, counter.get());
        worker.close();
    }
}
