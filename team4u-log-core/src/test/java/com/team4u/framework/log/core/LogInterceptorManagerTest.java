package com.team4u.framework.log.core;

import com.team4u.framework.log.pipeline.LogInterceptor;
import com.team4u.framework.log.pipeline.LogInterceptorManager;
import com.team4u.framework.log.pipeline.interceptor.MdcEnrichInterceptor;
import com.team4u.framework.log.pipeline.interceptor.RateLimitInterceptor;
import lombok.Getter;
import lombok.Setter;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;

import static com.team4u.framework.policy.api.ContextPolicy.HIGH;
import static com.team4u.framework.policy.api.ContextPolicy.LOW;
public class LogInterceptorManagerTest {

    @Test
    public void defaultsContainMdcRateAndSpiOnly() {
        LogInterceptorManager manager = new LogInterceptorManager();

        for (LogInterceptor interceptor : manager.getInterceptors()) {
            Assert.assertTrue(interceptor instanceof MdcEnrichInterceptor
                    || interceptor instanceof RateLimitInterceptor
                    || !isCoreBuiltIn(interceptor));
        }
    }

    private boolean isCoreBuiltIn(LogInterceptor interceptor) {
        return interceptor.getClass().getName().startsWith("com.team4u.framework.log");
    }

    @Test
    public void duplicateInstanceIsNotRegisteredTwice() {
        LogInterceptorManager manager = new LogInterceptorManager();
        MockInterceptor interceptor = new MockInterceptor();

        manager.register(interceptor);
        manager.register(interceptor);

        Assert.assertEquals(1, count(manager, interceptor));
    }

    @Test
    public void equalButDistinctInterceptorsStayIndependentlyOrderedAndOwned() {
        LogInterceptorManager manager = new LogInterceptorManager();
        EqualInterceptor first = new EqualInterceptor(LOW);
        EqualInterceptor second = new EqualInterceptor(HIGH);

        manager.register(first);
        manager.register(second);

        Assert.assertEquals(first, second);
        Assert.assertEquals(1, count(manager, first));
        Assert.assertEquals(1, count(manager, second));
        int firstPosition = position(manager, first);
        int secondPosition = position(manager, second);
        Assert.assertTrue("equal instances with different priorities must reverse insertion order: "
                + firstPosition + " vs " + secondPosition,
                firstPosition >= 0 && secondPosition >= 0 && secondPosition < firstPosition);

        manager.unregister(first);

        Assert.assertTrue(position(manager, second) >= 0);
    }

    @Test
    public void unregisterRemovesDefaultFromCoreOwnership() {
        LogInterceptorManager manager = new LogInterceptorManager();
        RateLimitInterceptor original = rate(manager);

        manager.unregister(original);
        manager.resetCore();

        Assert.assertTrue(original.handle(error("after-unregister")));
        original.stop();
        Assert.assertTrue(original.handle(error("after-unregister")));
        Assert.assertTrue(original.handle(error("after-unregister")));

        RateLimitInterceptor replacement = RateLimitInterceptor.create();
        replacement.setErrorLimitPerSecond(() -> 0);
        manager.register(replacement);
        manager.resetCore();
        LogEvent resetEvent = error("after-unregister");
        Assert.assertFalse(replacement.handle(resetEvent));
        Assert.assertTrue(resetEvent.isSuppressed());
    }

    @Test
    public void customInterceptorReset() {
        LogInterceptorManager manager = new LogInterceptorManager();
        MockInterceptor mock = new MockInterceptor();
        manager.register(mock);

        mock.setState(1);
        manager.reset();
        Assert.assertEquals(0, mock.getState());
    }

    @Test
    public void shouldProcessDisabledLevel() {
        LogInterceptorManager manager = new LogInterceptorManager();
        LogEvent event = new LogEvent();

        Assert.assertFalse(manager.shouldProcessDisabledLevel(event));
        BypassInterceptor interceptor = new BypassInterceptor();
        manager.register(interceptor);
        Assert.assertTrue(manager.shouldProcessDisabledLevel(event));
    }

    private int count(LogInterceptorManager manager, LogInterceptor target) {
        int count = 0;
        for (LogInterceptor interceptor : manager.getInterceptors()) {
            if (interceptor == target) {
                count++;
            }
        }
        return count;
    }
    private int position(LogInterceptorManager manager, LogInterceptor target) {
        List<LogInterceptor> interceptors = manager.getInterceptors();
        for (int i = 0; i < interceptors.size(); i++) {
            if (interceptors.get(i) == target) {
                return i;
            }
        }
        return -1;
    }

    private RateLimitInterceptor rate(LogInterceptorManager manager) {
        for (LogInterceptor interceptor : manager.getInterceptors()) {
            if (interceptor instanceof RateLimitInterceptor) {
                return (RateLimitInterceptor) interceptor;
            }
        }
        throw new AssertionError("Rate interceptor not installed");
    }

    private LogEvent error(String action) {
        return new LogEvent().setAction(action).setException(new RuntimeException(action));
    }

    private static class EqualInterceptor implements LogInterceptor {
        private final int priority;

        private EqualInterceptor(int priority) {
            this.priority = priority;
        }

        @Override
        public int priority() {
            return priority;
        }

        @Override
        public boolean equals(Object obj) {
            return obj != null && getClass() == obj.getClass();
        }

        @Override
        public int hashCode() {
            return getClass().hashCode();
        }

        @Override
        public boolean handle(LogEvent event) {
            return true;
        }
    }

    @Setter
    @Getter
    private static class MockInterceptor implements LogInterceptor {
        private int state = 0;

        @Override
        public void stop() {
            this.state = 0;
        }

        @Override
        public boolean handle(LogEvent event) {
            return true;
        }
    }

    private static class BypassInterceptor implements LogInterceptor {
        @Override
        public boolean handle(LogEvent event) {
            return true;
        }

        @Override
        public boolean shouldBypassLevelPrecheck(LogEvent event) {
            return true;
        }
    }
}
