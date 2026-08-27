package com.team4u.framework.base;

import com.team4u.framework.base.convert.ConvertUtil;
import com.team4u.framework.base.refresh.RefreshableValue;
import com.team4u.framework.base.util.TextTemplate;
import org.junit.Assert;
import org.junit.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class BaseQuickstartTest {

    private final MutableClock clock = new MutableClock();

    @Test
    public void textTemplateAndConvertQuickstart() {
        TextTemplate template = new TextTemplate("route:${region}.${tenant}");

        Assert.assertTrue(template.isDynamic());
        Assert.assertEquals(template.getVariableNames().toString(), "[region, tenant]");

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("region", "cn");
        context.put("tenant", 42);
        Assert.assertEquals("route:cn.42", template.render(context));

        Assert.assertEquals(Integer.valueOf(42), ConvertUtil.convert(Integer.class, "42"));
        Assert.assertEquals("42", ConvertUtil.toStr(42));
        Assert.assertEquals(Long.valueOf(7), ConvertUtil.toLong("7"));
    }

    @Test
    public void refreshableValueLifecycleAndRefreshQuickstart() {
        AtomicInteger loads = new AtomicInteger();
        RefreshableValue<String> value = RefreshableValue.<String>builder()
                .name("quickstart")
                .loader(context -> "v" + loads.incrementAndGet())
                .refreshEvery(Duration.ofSeconds(10))
                .clock(clock)
                .build();

        try {
            Assert.assertNull(value.peek());
            Assert.assertEquals("v1", value.get());
            Assert.assertEquals(1, value.status().getVersion());
            Assert.assertFalse(value.isStale());

            clock.advanceMillis(10_000);
            Assert.assertTrue(value.isStale());
            Assert.assertEquals("v2", value.get());
            Assert.assertEquals(2, value.status().getVersion());
            Assert.assertEquals(2, value.status().getRefreshCount());
            Assert.assertFalse(value.isStale());
        } finally {
            value.close();
        }

        Assert.assertTrue(value.status().isClosed());
        clock.advanceMillis(100_000);
        Assert.assertEquals("v2", value.get());
    }

    private static final class MutableClock extends Clock {
        private volatile Instant current = Instant.ofEpochMilli(1_000_000);

        void advanceMillis(long millis) {
            current = current.plusMillis(millis);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
