package com.team4u.framework.retry;

import com.team4u.framework.retry.backend.RetryTaskSnapshot;
import com.team4u.framework.retry.concurrent.RetryExecutorManager;
import com.team4u.framework.retry.recovery.RecoveryHandler;
import com.team4u.framework.retry.recovery.RecoveryHandlerRegistry;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
                .retryBackend(backend)
                .build();
    }

    @Test
    public void testExecuteSuccessWithWAL() throws Exception {
        AtomicInteger callCount = new AtomicInteger();

        String result = retryer.execute(taskType, context -> {
            RetryTaskSnapshot snapshot = new RetryTaskSnapshot();
            snapshot.setTaskId("id-" + System.currentTimeMillis());
            return snapshot;
        }, () -> {
            callCount.incrementAndGet();
            return "success";
        });

        Assert.assertEquals("success", result);
        Assert.assertEquals(1, callCount.get());

        // 验证 WAL 流程：保存了意图
        Assert.assertEquals(1, backend.savedSnapshots.size());
        // 由于是异步清理，稍微等待或简化模拟
        Thread.sleep(100);
        Assert.assertEquals(1, backend.deletedTaskIds.size());
    }

    @Test
    public void testExecuteExhaustedAndDowngrade() throws Exception {
        AtomicInteger callCount = new AtomicInteger();

        try {
            retryer.execute(taskType, context -> {
                RetryTaskSnapshot snapshot = new RetryTaskSnapshot();
                snapshot.setTaskId("id-exhausted");
                return snapshot;
            }, () -> {
                callCount.incrementAndGet();
                throw new RuntimeException("fail");
            });
            Assert.fail("应该抛出 RetryHandoffException");
        } catch (RetryHandoffException e) {
            Assert.assertTrue(e.getMessage().contains("In-memory retries exhausted"));
        }

        // 持久化模式下未显式配置 localAttempts，默认前台内存预算为 2 次
        Assert.assertEquals(2, callCount.get());
        // 验证 prepared intent 已被重调度到延迟队列
        Assert.assertEquals(1, backend.savedSnapshots.size());
        Assert.assertEquals(1, backend.delayedTasks.size());
        Assert.assertEquals(backend.savedSnapshots.values().iterator().next().getTaskId(),
                backend.delayedTasks.get(0).taskId);
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
        Map<String, RetryTaskSnapshot> savedSnapshots = new LinkedHashMap<>();
        List<String> deletedTaskIds = new ArrayList<>();
        List<DelayedTask> delayedTasks = new ArrayList<>();

        @Override
        public void prepare(RetryTaskSnapshot snapshot) {
            savedSnapshots.put(snapshot.getTaskId(), snapshot);
        }

        @Override
        public void complete(String taskId) {
            deletedTaskIds.add(taskId);
        }

        @Override
        public void handoff(String taskId, long delay) {
            delayedTasks.add(new DelayedTask(taskId, delay));
        }

        static class DelayedTask {
            String taskId;
            long delay;

            DelayedTask(String taskId, long delay) {
                this.taskId = taskId;
                this.delay = delay;
            }
        }
    }
}
