package com.team4u.framework.log.proxy;

import com.team4u.framework.log.integration.LogDynamicProxyTest;
import com.team4u.framework.proxy.ProxyBuilder;
import com.team4u.framework.proxy.core.MethodInvocation;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Method;

public class LogTraceSupportTest {

    @Test
    public void testGetTargetClassPrefersInvocationTarget() throws Exception {
        LogDynamicProxyTest.ThirdPartySmsClient target = new LogDynamicProxyTest.ThirdPartySmsClient();
        LogDynamicProxyTest.ThirdPartySmsClient proxy = ProxyBuilder.forClass(LogDynamicProxyTest.ThirdPartySmsClient.class)
                .withDelegate(target)
                .build();

        Method method = LogDynamicProxyTest.ThirdPartySmsClient.class.getMethod(
                "send", String.class, String.class, String.class);

        MethodInvocation invocation = new StubInvocation(proxy, target, method);
        Assert.assertSame(LogDynamicProxyTest.ThirdPartySmsClient.class,
                LogTraceSupport.getTargetClass(invocation, method));
    }

    @Test
    public void testGetTargetClassFallsBackToMethodDeclaringClass() throws Exception {
        Method method = LogDynamicProxyTest.ThirdPartyPaymentApi.class.getMethod("pay", String.class, int.class);
        MethodInvocation invocation = new StubInvocation(null, null, method);

        Assert.assertSame(LogDynamicProxyTest.ThirdPartyPaymentApi.class,
                LogTraceSupport.getTargetClass(invocation, method));
    }

    @Test
    public void testGetTargetClassUsesUserClassForByteBuddyProxyWithoutTarget() throws Exception {
        LogDynamicProxyTest.ThirdPartySmsClient proxy = ProxyBuilder.forClass(LogDynamicProxyTest.ThirdPartySmsClient.class)
                .withDelegate(new LogDynamicProxyTest.ThirdPartySmsClient())
                .build();
        Method method = LogDynamicProxyTest.ThirdPartySmsClient.class.getMethod(
                "send", String.class, String.class, String.class);

        MethodInvocation invocation = new StubInvocation(proxy, null, method);
        Assert.assertSame(LogDynamicProxyTest.ThirdPartySmsClient.class,
                LogTraceSupport.getTargetClass(invocation, method));
    }

    @Test
    public void testGetTargetClassFallsBackToMethodDeclaringClassWhenInvocationIsNull() throws Exception {
        Method method = LogDynamicProxyTest.ThirdPartyPaymentApi.class.getMethod("pay", String.class, int.class);

        Assert.assertSame(LogDynamicProxyTest.ThirdPartyPaymentApi.class,
                LogTraceSupport.getTargetClass((MethodInvocation) null, method));
    }

    @Test
    public void testGetTargetClassReturnsObjectClassWhenNoInvocationOrMethodData() {
        MethodInvocation invocation = new StubInvocation(null, null, null);

        Assert.assertSame(Object.class,
                LogTraceSupport.getTargetClass(invocation, null));
    }

    private static class StubInvocation implements MethodInvocation {
        private final Object proxy;
        private final Object target;
        private final Method method;

        private StubInvocation(Object proxy, Object target, Method method) {
            this.proxy = proxy;
            this.target = target;
            this.method = method;
        }

        @Override
        public Object getProxy() {
            return proxy;
        }

        @Override
        public Object getTarget() {
            return target;
        }

        @Override
        public Method getMethod() {
            return method;
        }

        @Override
        public Object[] getArguments() {
            return new Object[0];
        }

        @Override
        public Object proceed() {
            return null;
        }
    }
}
