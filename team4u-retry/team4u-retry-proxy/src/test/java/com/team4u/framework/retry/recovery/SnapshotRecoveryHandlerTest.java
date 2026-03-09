package com.team4u.framework.retry.recovery;

import com.team4u.framework.bean.BeanManager;
import com.team4u.framework.proxy.ProxyBuilder;
import com.team4u.framework.retry.RetryPolicy;
import com.team4u.framework.retry.TestLeaseBackend;
import com.team4u.framework.retry.backend.RetryCloseRequest;
import com.team4u.framework.retry.backend.RetryCloseRequest;
import com.team4u.framework.retry.backend.RetryTaskSnapshot;
import com.team4u.framework.retry.policy.NamedRetryPolicy;
import com.team4u.framework.retry.policy.RetryPolicyRegistry;
import com.team4u.framework.retry.proxy.RetryInterceptor;
import com.team4u.framework.retry.proxy.RetryProxyFactory;
import com.team4u.framework.retry.proxy.Retryable;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SnapshotRecoveryHandler 恢复器测试
 * <p>
 * 验证通过任务快照恢复原始方法执行现场的准确性与幂等性。
 */
public class SnapshotRecoveryHandlerTest {

    @Before
    public void setup() {
        RetryPolicyRegistry.global().unregisterAll();
        RecoveryHandlerRegistry.global().unregisterAll();
        RetryPolicyRegistry.global().register(new NamedRetryPolicy() {
            @Override
            public String key() {
                return "snapshot-recovery";
            }

            @Override
            public RetryPolicy getPolicy() {
                return RetryPolicy.builder()
                        .maxAttempts(1)
                        .build();
            }
        });
    }

    @Test
    public void testDefaultProxyRecoveryHandlerRegistrationIsIdempotent() {
        RetryProxyFactory.registerDefaultRecoveryHandler();
        RetryProxyFactory.registerDefaultRecoveryHandler();

        Assert.assertTrue(RecoveryHandlerRegistry.global().get(RetryTaskTypes.DEFAULT_PROXY_RECOVERY).isPresent());
        Assert.assertEquals(1, RecoveryHandlerRegistry.global().getPolicies().size());
    }

    @Test
    public void testRecoverInvokesBeanWithoutReEnteringRetryPipeline() throws Exception {
        CountingBackend backend = new CountingBackend();
        RecoveryServiceImpl target = new RecoveryServiceImpl();
        RecoveryService proxy = ProxyBuilder.forClass(RecoveryService.class)
                .withDelegate(target)
                .addInterceptor(new RetryInterceptor(backend))
                .build();
        BeanManager.getInstance().registerBean(RecoveryService.class.getName(), proxy);

        RetryTaskSnapshot snapshot = new RetryTaskSnapshot();
        snapshot.setTaskType("recover-task");
        snapshot.setBeanName(RecoveryService.class.getName());
        snapshot.setMethodName("process");
        snapshot.setArgTypes(Arrays.asList(String.class.getName(), int.class.getName()));
        snapshot.setArgJsonValues(Arrays.asList("\"payload\"", "3"));

        SnapshotRecoveryHandler handler = new SnapshotRecoveryHandler("recover-task");
        handler.recover(snapshot);

        Assert.assertEquals(1, target.invokeCount.get());
        Assert.assertEquals("payload", target.lastValue);
        Assert.assertEquals(3, target.lastTimes);
        Assert.assertEquals(0, backend.saveCount.get());
        Assert.assertEquals(0, backend.submitCount.get());
    }

    public interface RecoveryService {
        @Retryable(policy = "snapshot-recovery")
        void process(String value, int times);
    }

    public static class RecoveryServiceImpl implements RecoveryService {
        private final AtomicInteger invokeCount = new AtomicInteger();
        private String lastValue;
        private int lastTimes;

        @Override
        public void process(String value, int times) {
            invokeCount.incrementAndGet();
            lastValue = value;
            lastTimes = times;
        }
    }

    private static class CountingBackend extends TestLeaseBackend {
        private final AtomicInteger saveCount = new AtomicInteger();
        private final AtomicInteger submitCount = new AtomicInteger();

        @Override
        public void prepare(RetryTaskSnapshot snapshot) {
            saveCount.incrementAndGet();
            snapshot.setTaskId("intent");
        }

        @Override
        public void handoff(String taskId, long delayMillis) {
            submitCount.incrementAndGet();
        }
    }
}