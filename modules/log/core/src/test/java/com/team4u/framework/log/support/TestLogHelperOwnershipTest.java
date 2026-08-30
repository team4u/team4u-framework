package com.team4u.framework.log.support;

import com.team4u.framework.log.Loggers;
import com.team4u.framework.log.appender.CompositeLogAppender;
import com.team4u.framework.log.appender.LogAppender;
import com.team4u.framework.log.appender.Slf4jLogAppender;
import com.team4u.framework.log.core.LogEngine;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.event.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class TestLogHelperOwnershipTest {

    private LogEngine engine;

    @Before
    public void setUp() {
        engine = LogEngine.getInstance();
        engine.setAppender(new Slf4jLogAppender());
    }

    @After
    public void tearDown() {
        engine.setAppender(new Slf4jLogAppender());
    }

    @Test
    public void nestedHelpersRestoreOriginalWhenOuterStopsFirst() {
        LogAppender original = engine.getAppender();
        TestLogHelper outer = TestLogHelper.start();
        TestLogHelper inner = TestLogHelper.start();

        log("before-stop");
        Assert.assertEquals(1, outer.allEvents().size());
        Assert.assertEquals(1, inner.allEvents().size());

        outer.stop();
        log("after-outer-stop");
        Assert.assertEquals(1, outer.allEvents().size());
        Assert.assertEquals(2, inner.allEvents().size());

        inner.stop();
        Assert.assertSame(original, engine.getAppender());
        log("after-both-stop");
        Assert.assertEquals(1, outer.allEvents().size());
        Assert.assertEquals(2, inner.allEvents().size());
        inner.clear();
    }

    @Test
    public void threeNestedHelpersCollapseInEveryStopOrder() {
        int[][] stopOrders = {
                {0, 1, 2},
                {0, 2, 1},
                {1, 0, 2},
                {1, 2, 0},
                {2, 0, 1},
                {2, 1, 0}
        };

        for (int[] stopOrder : stopOrders) {
            LogAppender original = new Slf4jLogAppender();
            engine.setAppender(original);

            List<TestLogHelper> helpers = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                helpers.add(TestLogHelper.start());
                log("nested-" + i);
            Assert.assertEquals(1, helpers.get(i).allEvents().size());
            }

            for (int i = stopOrder.length - 1; i >= 0; i--) {
                helpers.get(stopOrder[i]).stop();
            }

            Assert.assertSame(
                    "original not restored for order " + java.util.Arrays.toString(stopOrder)
                            + ", actual=" + structure(engine.getAppender()),
                    original, engine.getAppender());
        }
    }

    @Test
    public void concurrentCustomAppenderWinsOverHelperStop() throws Exception {
        for (int iteration = 0; iteration < 200; iteration++) {
            LogAppender original = new Slf4jLogAppender();
            LogAppender custom = new Slf4jLogAppender();
            engine.setAppender(original);
            TestLogHelper helper = TestLogHelper.start();

            CountDownLatch start = new CountDownLatch(1);
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<?> setResult = executor.submit(() -> {
                start.await();
                LogEngine.getInstance().setAppender(custom);
                return null;
            });

            start.countDown();
            helper.stop();
            setResult.get(5, TimeUnit.SECONDS);
            executor.shutdown();

            Assert.assertSame(custom, engine.getAppender());
        }
    }

    @Test
    public void concurrentEngineInstallPreservesCapturedAppender() throws Exception {
        LogEngine baseline = LogEngine.builder().build();
        LogEngine.install(baseline);

        for (int iteration = 0; iteration < 200; iteration++) {
            LogAppender original = new Slf4jLogAppender();
            baseline.setAppender(original);
            TestLogHelper helper = TestLogHelper.start();

            LogEngine replacement = LogEngine.builder().build();
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<?> installResult = executor.submit(() -> {
                start.await();
                LogEngine.install(replacement);
                return null;
            });

            start.countDown();
            installResult.get(5, TimeUnit.SECONDS);
            executor.shutdown();

            helper.stop();

            Assert.assertSame(replacement, LogEngine.getInstance());
            Assert.assertSame(original, replacement.getAppender());

            baseline.setAppender(original);
            Assert.assertTrue(LogEngine.restore(replacement, baseline));
        }

        baseline.setAppender(new Slf4jLogAppender());
        Assert.assertTrue(LogEngine.restore(baseline, engine));
    }

    @Test
    public void helperNeverCollapsesAUserComposite() {
        LogAppender userChild = new Slf4jLogAppender();
        CompositeLogAppender userComposite = new CompositeLogAppender(userChild);
        engine.setAppender(userComposite);
        TestLogHelper helper = TestLogHelper.start();

        helper.stop();

        Assert.assertSame(userComposite, engine.getAppender());
        Assert.assertEquals(1, userComposite.getAppenders().size());
        Assert.assertSame(userChild, userComposite.getAppenders().get(0));
    }

    private void log(String action) {
        Loggers.of(getClass()).action(action).level(Level.INFO).log();
    }

    private static String structure(LogAppender appender) {
        if (!(appender instanceof CompositeLogAppender)) {
            return appender.getClass().getSimpleName() + '@' + Integer.toHexString(System.identityHashCode(appender));
        }
        CompositeLogAppender composite = (CompositeLogAppender) appender;
        StringBuilder value = new StringBuilder("Composite@").append(Integer.toHexString(System.identityHashCode(composite)));
        for (LogAppender child : composite.getAppenders()) {
            value.append(" -> ").append(structure(child));
        }
        return value.toString();
    }
}
