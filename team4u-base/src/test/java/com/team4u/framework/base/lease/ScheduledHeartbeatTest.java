package com.team4u.framework.base.lease;

import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ScheduledHeartbeat 单元测试
 * <p>
 * 使用极短的 leaseMillis/intervalMillis 验证：正常续约、失败停止并回调、
 * 异常容忍不弃权、stop 幂等、start 幂等。
 * </p>
 *
 * @author jay.wu
 */
public class ScheduledHeartbeatTest {

    private static final long LEASE_MILLIS = 300L;
    private static final long INTERVAL_MILLIS = 60L;

    @Test
    public void renewPeriodically() throws Exception {
        AtomicInteger renewCount = new AtomicInteger();
        ScheduledHeartbeat heartbeat = ScheduledHeartbeat
                .builder("token-1", LEASE_MILLIS, token -> {
                    Assert.assertEquals("续约应收到持有者令牌", "token-1", token);
                    renewCount.incrementAndGet();
                    return true;
                })
                .intervalMillis(INTERVAL_MILLIS)
                .build();

        Assert.assertFalse("未 start 不应运行", heartbeat.isRunning());
        heartbeat.start();
        Assert.assertTrue("start 后应运行", heartbeat.isRunning());
        Assert.assertEquals("间隔应取配置值", INTERVAL_MILLIS, heartbeat.getIntervalMillis());

        // 等待至少 4 次心跳
        waitUntil(() -> renewCount.get() >= 4, 2_000L);
        Assert.assertTrue("应发生多次续约，实际 " + renewCount.get(), renewCount.get() >= 4);
        Assert.assertTrue("持续续约期间应保持运行", heartbeat.isRunning());

        heartbeat.stop();
        Assert.assertFalse("stop 后不应运行", heartbeat.isRunning());
        heartbeat.stop();
    }

    @Test
    public void defaultIntervalIsLeaseDividedByThree() {
        ScheduledHeartbeat heartbeat = ScheduledHeartbeat
                .builder("token-default", LEASE_MILLIS, token -> true)
                .build();
        Assert.assertEquals("默认间隔应为 lease/3", LEASE_MILLIS / 3L, heartbeat.getIntervalMillis());
        heartbeat.stop();
    }

    @Test
    public void renewFailureStopsAndFiresOnLost() throws Exception {
        AtomicInteger renewCount = new AtomicInteger();
        CountDownLatch lostLatch = new CountDownLatch(1);
        AtomicReference<String> lostToken = new AtomicReference<>();

        ScheduledHeartbeat heartbeat = ScheduledHeartbeat
                .builder("token-lost", LEASE_MILLIS, token -> {
                    renewCount.incrementAndGet();
                    // 第二次续约失败：租约被接管
                    return renewCount.get() < 2;
                })
                .intervalMillis(INTERVAL_MILLIS)
                .onLost(() -> {
                    lostToken.set("token-lost");
                    lostLatch.countDown();
                })
                .build();
        heartbeat.start();

        Assert.assertTrue("onLost 应在失败后被回调", lostLatch.await(2L, TimeUnit.SECONDS));
        Assert.assertEquals("onLost 回调上下文异常", "token-lost", lostToken.get());
        Assert.assertFalse("丢失后应自动停止", heartbeat.isRunning());

        int countAfterLoss = renewCount.get();
        // 停止后不再有心跳
        Thread.sleep(200L);
        Assert.assertEquals("停止后不应继续续约", countAfterLoss, renewCount.get());

        // 丢失后再 stop 仍应幂等安全
        heartbeat.stop();
    }

    @Test
    public void renewExceptionIsTolerated() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch successLatch = new CountDownLatch(1);
        ScheduledHeartbeat heartbeat = ScheduledHeartbeat
                .builder("token-err", LEASE_MILLIS, token -> {
                    if (attempts.incrementAndGet() == 1) {
                        throw new IllegalStateException("transient store failure");
                    }
                    successLatch.countDown();
                    return true;
                })
                .intervalMillis(INTERVAL_MILLIS)
                .build();
        heartbeat.start();

        // 首次抛异常后心跳不应停止，第二次续约照常执行
        Assert.assertTrue("瞬时异常后应继续心跳", successLatch.await(2L, TimeUnit.SECONDS));
        Assert.assertTrue("异常容忍期间应保持运行", heartbeat.isRunning());
        heartbeat.stop();
    }

    @Test
    public void stopIsIdempotent() {
        ScheduledHeartbeat heartbeat = ScheduledHeartbeat
                .builder("token-stop", LEASE_MILLIS, token -> true)
                .build();
        heartbeat.start();
        heartbeat.stop();
        // 多次 stop 幂等
        heartbeat.stop();
        heartbeat.stop();
        Assert.assertFalse(heartbeat.isRunning());
    }

    @Test
    public void startIsIdempotent() {
        AtomicInteger renewCount = new AtomicInteger();
        ScheduledHeartbeat heartbeat = ScheduledHeartbeat
                .builder("token-idem", LEASE_MILLIS, token -> {
                    renewCount.incrementAndGet();
                    return true;
                })
                .intervalMillis(INTERVAL_MILLIS)
                .build();
        Assert.assertSame("重复 start 应返回自身", heartbeat, heartbeat.start());
        heartbeat.start();
        try {
            Thread.sleep(250L);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        // 重复 start 不会重复调度：单一调度任务的续约节奏
        int count = renewCount.get();
        Assert.assertTrue("重复 start 后仍只有一个心跳任务在跑", count >= 1 && count <= 8);
    }

    @Test
    public void startAfterStopIsRejected() {
        ScheduledHeartbeat heartbeat = ScheduledHeartbeat
                .builder("token-after-stop", LEASE_MILLIS, token -> true)
                .build();
        heartbeat.start();
        heartbeat.stop();
        try {
            heartbeat.start();
            Assert.fail("stop 后重启应抛 IllegalStateException");
        } catch (IllegalStateException expected) {
            // 预期：一个实例只对应一段持有期
        }
    }

    @Test
    public void onLostCallbackFailureIsSwallowed() throws Exception {
        CountDownLatch lostLatch = new CountDownLatch(1);
        AtomicInteger renewCount = new AtomicInteger();
        ScheduledHeartbeat heartbeat = ScheduledHeartbeat
                .builder("token-cb-err", LEASE_MILLIS, token -> renewCount.incrementAndGet() < 1)
                .intervalMillis(INTERVAL_MILLIS)
                .onLost(() -> {
                    lostLatch.countDown();
                    throw new RuntimeException("callback boom");
                })
                .build();
        heartbeat.start();
        Assert.assertTrue("回调抛异常不影响丢失通知本身", lostLatch.await(2L, TimeUnit.SECONDS));
        Assert.assertFalse(heartbeat.isRunning());
    }

    @Test
    public void builderValidation() {
        try {
            ScheduledHeartbeat.builder("  ", LEASE_MILLIS, token -> true);
            Assert.fail("空白 token 应被拒绝");
        } catch (IllegalArgumentException expected) {
            // 预期
        }
        try {
            ScheduledHeartbeat.builder("t", 0L, token -> true);
            Assert.fail("非正数 leaseMillis 应被拒绝");
        } catch (IllegalArgumentException expected) {
            // 预期
        }
        try {
            ScheduledHeartbeat.builder("t", LEASE_MILLIS, null);
            Assert.fail("null operation 应被拒绝");
        } catch (NullPointerException expected) {
            // 预期
        }
        try {
            ScheduledHeartbeat.builder("t", LEASE_MILLIS, token -> true)
                    .intervalMillis(LEASE_MILLIS).build();
            Assert.fail("间隔不小于租约应被拒绝");
        } catch (IllegalArgumentException expected) {
            // 预期
        }
        try {
            ScheduledHeartbeat.builder("t", LEASE_MILLIS, token -> true)
                    .intervalMillis(0L).build();
            Assert.fail("非正数间隔应被拒绝");
        } catch (IllegalArgumentException expected) {
            // 预期
        }
    }

    @Test
    public void accessors() {
        ScheduledHeartbeat heartbeat = ScheduledHeartbeat
                .builder("token-acc", 1_000L, token -> true)
                .intervalMillis(100L)
                .build();
        Assert.assertEquals("token", "token-acc", heartbeat.getToken());
        Assert.assertEquals("leaseMillis", 1_000L, heartbeat.getLeaseMillis());
        Assert.assertEquals("intervalMillis", 100L, heartbeat.getIntervalMillis());
        heartbeat.close();
        Assert.assertFalse(heartbeat.isRunning());
    }

    /**
     * 轮询等待条件成立，超时返回
     */
    private static void waitUntil(java.util.function.BooleanSupplier condition, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(10L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
