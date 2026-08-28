package com.team4u.framework.ratelimiter.core;

import com.team4u.framework.ratelimiter.api.RateLimitReason;
import com.team4u.framework.ratelimiter.api.RateLimitResult;
import com.team4u.framework.ratelimiter.config.RateLimitRule;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 历史窗口算法单元测试：对齐窗口计数、跨窗归零、许可数、路径缺失、Bean 嵌套路径
 *
 * @author jay.wu
 */
public class HistoryWindowAlgorithmTest {

    private final HistoryWindowAlgorithm algorithm = new HistoryWindowAlgorithm();

    private static RateLimitRule rule(long windowMillis, long threshold, String historyPath) {
        RateLimitRule rule = new RateLimitRule();
        rule.setId("hw");
        rule.setAlgorithm(HistoryWindowAlgorithm.KEY);
        rule.setWindowMillis(windowMillis);
        rule.setThreshold(threshold);
        rule.setHistoryPath(historyPath);
        return rule;
    }

    private RateLimitResult acquire(RateLimitRule rule, Object context, long nowMillis, int permits) {
        return algorithm.tryAcquire(rule, null, "hw.client", context, nowMillis, permits);
    }

    @Test
    public void thresholdReachedWithinAlignedWindow() {
        RateLimitRule rule = rule(1000, 2, "history");
        // now=1500 → windowStart=1000；两条历史均落在当前对齐窗口内
        Map<String, Object> context = Collections.singletonMap("history",
                Arrays.asList(1000L, 1400L));

        RateLimitResult denied = acquire(rule, context, 1500, 1);
        assertFalse(denied.isAllowed());
        assertEquals(RateLimitReason.THRESHOLD, denied.getReason());
        assertEquals(Long.valueOf(0L), denied.getRemaining());
        // 窗口剩余时间：2000-1500
        assertEquals(Long.valueOf(500L), denied.getRetryAfterMillis());
        assertEquals(1500L, denied.getDecisionTimeMillis());
    }

    @Test
    public void counterResetsAfterWindowBoundary() {
        RateLimitRule rule = rule(1000, 2, "history");
        Map<String, Object> context = Collections.singletonMap("history",
                Arrays.asList(1000L, 1400L));

        // now=2500 → windowStart=2000，旧历史全部出窗，计数归零（remaining = threshold - count）
        RateLimitResult reset = acquire(rule, context, 2500, 1);
        assertTrue(reset.isAllowed());
        assertEquals(Long.valueOf(2L), reset.getRemaining());
        assertEquals(Long.valueOf(500L), reset.getRetryAfterMillis());
    }

    @Test
    public void permitsAccountedAgainstHistory() {
        RateLimitRule rule = rule(1000, 3, "history");
        Map<String, Object> context = Collections.singletonMap("history",
                Collections.singletonList(1200L));

        assertTrue("1 历史 + 2 许可 = 3 <= 3", acquire(rule, context, 1500, 2).isAllowed());
        assertFalse("2 历史 + 2 许可 = 4 > 3",
                acquire(rule, contextOf(1200L, 1300L), 1500, 2).isAllowed());
    }

    @Test
    public void futureTimestampsCountIntoCurrentWindow() {
        RateLimitRule rule = rule(1000, 2, "history");
        // 未来时间戳也计入当前窗口（客户端时钟超前不放大额度）
        Map<String, Object> context = Collections.singletonMap("history",
                Arrays.asList(1800L, 1900L));
        assertFalse(acquire(rule, context, 1500, 1).isAllowed());
    }

    @Test
    public void missingHistoryPathTreatedAsEmpty() {
        RateLimitRule rule = rule(1000, 1, "not.exist.path");
        RateLimitResult result = acquire(rule, new HashMap<String, Object>(), 1500, 1);
        assertTrue(result.isAllowed());
        assertEquals(Long.valueOf(1L), result.getRemaining());
    }

    @Test
    public void beanNestedPathExtraction() {
        RateLimitRule rule = rule(1000, 2, "meta.history");
        Meta meta = new Meta(Arrays.asList(new Date(1100L), new Date(1200L)));
        Order order = new Order("O1", meta);

        RateLimitResult denied = acquire(rule, order, 1500, 1);
        assertFalse(denied.isAllowed());
        assertEquals(Long.valueOf(0L), denied.getRemaining());

        // Date 元素按 epoch 毫秒计入；跨窗后归零
        RateLimitResult reset = acquire(rule, order, 2500, 1);
        assertTrue(reset.isAllowed());
    }

    private static Map<String, Object> contextOf(Object... timestamps) {
        return Collections.singletonMap("history", Arrays.asList(timestamps));
    }

    /**
     * Bean 嵌套路径载体：order.meta.history
     */
    public static class Order {

        private final String id;
        private final Meta meta;

        Order(String id, Meta meta) {
            this.id = id;
            this.meta = meta;
        }

        public String getId() {
            return id;
        }

        public Meta getMeta() {
            return meta;
        }
    }

    public static class Meta {

        private final java.util.List<Date> history;

        Meta(java.util.List<Date> history) {
            this.history = history;
        }

        public java.util.List<Date> getHistory() {
            return history;
        }
    }
}
