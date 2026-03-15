package com.team4u.framework.log.proxy;

import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Method;

public class AutoLogTraceResolverTest {

    @Test
    public void testResolvePrefersMethodAnnotation() throws Exception {
        Method method = MethodAnnotatedService.class.getMethod("call");

        AutoLogTrace trace = AutoLogTraceResolver.resolve(MethodAnnotatedService.class, method);
        Assert.assertNotNull(trace);
        Assert.assertEquals("method", trace.action());
    }

    @Test
    public void testResolveFallsBackToTargetMethodAnnotation() throws Exception {
        Method method = ServiceApi.class.getMethod("call");

        AutoLogTrace trace = AutoLogTraceResolver.resolve(TargetMethodAnnotatedService.class, method);
        Assert.assertNotNull(trace);
        Assert.assertEquals("target-method", trace.action());
    }

    @Test
    public void testResolveFallsBackToTargetClassAnnotation() throws Exception {
        Method method = ServiceApi.class.getMethod("call");

        AutoLogTrace trace = AutoLogTraceResolver.resolve(ClassAnnotatedService.class, method);
        Assert.assertNotNull(trace);
        Assert.assertEquals("class-level", trace.action());
    }

    @Test
    public void testResolveFallsBackToDeclaringClassAnnotation() throws Exception {
        Method method = DeclaringClassAnnotatedApi.class.getMethod("call");

        AutoLogTrace trace = AutoLogTraceResolver.resolve(Object.class, method);
        Assert.assertNotNull(trace);
        Assert.assertEquals("declaring-class", trace.action());
    }

    interface ServiceApi {
        void call();
    }

    @AutoLogTrace(action = "declaring-class")
    interface DeclaringClassAnnotatedApi {
        void call();
    }

    static class MethodAnnotatedService {
        @AutoLogTrace(action = "method")
        public void call() {
        }
    }

    static class TargetMethodAnnotatedService implements ServiceApi {
        @Override
        @AutoLogTrace(action = "target-method")
        public void call() {
        }
    }

    @AutoLogTrace(action = "class-level")
    static class ClassAnnotatedService implements ServiceApi {
        @Override
        public void call() {
        }
    }
}
