package com.team4u.framework.retry;

import com.team4u.framework.retry.concurrent.RetryExecutorManager;
import com.team4u.framework.retry.recovery.RecoveryHandler;
import com.team4u.framework.retry.recovery.RecoveryHandlerRegistry;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 编程式重试集成测试
 */
public class ProgrammaticRetryIntegrationTest {

    private final String taskType = "test.task";
    private final String payload = "{\"id\":1}";
    private MockLeaseBackend backend;
    private Retryer retryer;

    @Before
    public void setUp() {
        // 确保每次测试前线程池均处于可用状态，
        // 防止 Spring 上下文关闭后静态单例被永久关闭
        RetryExecutorManager.global().reset();

        backend = new MockLeaseBackend();
        retryer = Retryer.builder()
                .policy(RetryPolicy.builder().maxAttempts(3).build())
                .backend(backend)
                .durability(RetryDurability.AT_LEAST_ONCE_DURABLE)
                .build();
    }

    @Test
    public void testExecuteSuccessWithWAL() throws Exception {
        AtomicInteger callCount = new AtomicInteger();

        String result = retryer.execute(taskType, context -> payload, () -> {
            callCount.incrementAndGet();
            return "success";
        });

        Assert.assertEquals("success", result);
        Assert.assertEquals(1, callCount.get());

        // 验证 WAL 流程：保存了意图
        Assert.assertEquals(1, backend.savedIntents.size());
        // 由于是异步清理，稍微等待或简化模拟
        Thread.sleep(100);
        Assert.assertEquals(1, backend.completedIntents.size());
    }

    @Test
    public void testExecuteExhaustedAndDowngrade() throws Exception {
        AtomicInteger callCount = new AtomicInteger();

        try {
            retryer.execute(taskType, context -> payload, () -> {
                callCount.incrementAndGet();
                throw new RuntimeException("fail");
            });
            Assert.fail("应该抛出 RetryExhaustedException");
        } catch (RetryExhaustedException e) {
            Assert.assertTrue(e.getMessage().contains("In-memory retries exhausted"));
        }

        // AT_LEAST_ONCE_DURABLE 下未显式配置 inMemoryAttempts，默认前台内存预算为 2 次
        Assert.assertEquals(2, callCount.get());
        // 验证任务已提交到延迟队列
        Assert.assertEquals(1, backend.delayedIntents.size());
        Assert.assertEquals(taskType, backend.delayedIntents.get(0).taskType);
    }

    @Test
    public void testRecoveryHandler() throws Exception {
        AtomicInteger recoveredCount = new AtomicInteger();

        RecoveryHandler handler = new RecoveryHandler() {
            @Override
            public String key() {
                return taskType;
            }

            @Override
            public void recover(String p) {
                Assert.assertEquals(payload, p);
                recoveredCount.incrementAndGet();
            }
        };

        RecoveryHandlerRegistry.global().register(handler);

        // 模拟 Worker 捞起任务并恢复
        RecoveryHandlerRegistry.global().get(taskType).ifPresent(h -> {
            try {
                h.recover(payload);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        Assert.assertEquals(1, recoveredCount.get());
    }

    /**
     * 模拟后端实现
     */
    private static class MockLeaseBackend extends TestLeaseBackend {
        List<Intent> savedIntents = new ArrayList<>();
        List<String> completedIntents = new ArrayList<>();
        List<Intent> delayedIntents = new ArrayList<>();

        @Override
        public String saveIntent(String taskType, String payload) {
            String id = "id-" + System.currentTimeMillis();
            savedIntents.add(new Intent(id, taskType, payload));
            return id;
        }

        @Override
        public void completeIntent(String intentId) {
            completedIntents.add(intentId);
        }


        @Override
        public void markTerminalFailure(String intentId, Throwable cause) {
        }

        @Override
        public void submitForDelay(String intentId, String taskType, String payload, long delay) {
            delayedIntents.add(new Intent(intentId, taskType, payload));
        }

        static class Intent {
            String id;
            String taskType;
            String payload;

            Intent(String id, String taskType, String payload) {
                this.id = id;
                this.taskType = taskType;
                this.payload = payload;
            }
        }
    }
}
