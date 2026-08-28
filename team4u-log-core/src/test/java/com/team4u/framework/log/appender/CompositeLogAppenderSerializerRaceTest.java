package com.team4u.framework.log.appender;

import com.team4u.framework.log.core.LogEvent;
import com.team4u.framework.log.core.LogSerializer;
import com.team4u.framework.log.core.PlainTextLogSerializer;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class CompositeLogAppenderSerializerRaceTest {

    @Test
    public void concurrentAddAndBindNeverLeavesAChildWithAnOldSerializer() throws Exception {
        CompositeLogAppender composite = new CompositeLogAppender();
        int iterations = 300;
        int binders = 2;
        int adders = 2;
        ExecutorService executor = Executors.newFixedThreadPool(binders + adders);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(binders + adders);
        AtomicInteger bindCount = new AtomicInteger();

        for (int i = 0; i < binders; i++) {
            final int worker = i;
            executor.submit(() -> {
                try {
                    start.await();
                    for (int j = 0; j < iterations; j++) {
                        composite.bindSerializer(new PrefixSerializer("bind-" + worker + "-" + j));
                        bindCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        for (int i = 0; i < adders; i++) {
            executor.submit(() -> {
                try {
                    start.await();
                    for (int j = 0; j < iterations; j++) {
                        composite.addAppender(new RecordingAwareAppender());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        Assert.assertTrue(done.await(5, TimeUnit.SECONDS));
        executor.shutdown();

        LogSerializer finalSerializer = new PrefixSerializer("final");
        composite.bindSerializer(finalSerializer);
        List<LogAppender> children = composite.getAppenders();
        Assert.assertEquals(iterations * adders, children.size());
        for (LogAppender appender : children) {
            Assert.assertSame(finalSerializer, ((RecordingAwareAppender) appender).serializer);
        }
        Assert.assertEquals(iterations * binders, bindCount.get());
    }

    private static final class PrefixSerializer implements LogSerializer {
        private final String prefix;

        private PrefixSerializer(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public String serialize(LogEvent event) {
            return prefix;
        }

        @Override
        public void reset() {
            // Serializer state is not relevant to this race.
        }
    }

    private static final class RecordingAwareAppender implements SerializerAwareLogAppender {
        private volatile LogSerializer serializer = new PlainTextLogSerializer();

        @Override
        public void append(LogEvent event) {
        }

        @Override
        public void bindSerializer(LogSerializer serializer) {
            this.serializer = serializer;
        }
    }
}
