package com.team4u.framework.log.core;

import com.team4u.framework.log.pipeline.LogInterceptor;
import com.team4u.framework.log.pipeline.LogInterceptorManager;
import com.team4u.framework.log.pipeline.interceptor.MdcEnrichInterceptor;
import com.team4u.framework.log.pipeline.interceptor.RateLimitInterceptor;
import lombok.Getter;
import lombok.Setter;
import org.junit.Assert;
import org.junit.Test;

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
