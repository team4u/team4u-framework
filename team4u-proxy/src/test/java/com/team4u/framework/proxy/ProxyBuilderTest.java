package com.team4u.framework.proxy;

import com.team4u.framework.proxy.support.Swappable;
import com.team4u.framework.proxy.support.Tracker;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public class ProxyBuilderTest {

    /**
     * 测试场景 1：普通接口代理 + 委托转发
     */
    @Test
    public void testInterfaceProxy() {
        ProxySample target = message -> "Hello, " + message;
        ProxySample proxy = ProxyBuilder.forClass(ProxySample.class)
                .withDelegate(target)
                .build();

        Assert.assertEquals("Hello, World", proxy.execute("World"));
    }

    /**
     * 测试场景 2：基于 ByteBuddy 的类代理（非接口）
     */
    @Test
    public void testClassProxy() {
        SampleService target = new SampleService();
        SampleService proxy = ProxyBuilder.forClass(SampleService.class)
                .withDelegate(target)
                .build();

        Assert.assertEquals("Service: test", proxy.hello("test"));
    }

    /**
     * 测试场景 3：追踪器拦截
     */
    @Test
    public void testTracker() {
        List<String> logs = new ArrayList<>();
        ProxySample target = message -> "Hello, " + message;
        ProxySample proxy = ProxyBuilder.forClass(ProxySample.class)
                .withDelegate(target)
                .withTracker(new Tracker() {
                    @Override
                    public void before(Object proxy, Method method, Object[] args) {
                        logs.add("before");
                    }

                    @Override
                    public void after(Object proxy, Method method, Object[] args, Object result) {
                        logs.add("after");
                    }

                    @Override
                    public void onException(Object proxy, Method method, Object[] args, Throwable e) {
                        logs.add("exception");
                    }
                })
                .build();

        proxy.execute("test");
        Assert.assertEquals(2, logs.size());
        Assert.assertEquals("before", logs.get(0));
        Assert.assertEquals("after", logs.get(1));
    }

    /**
     * 测试场景 4：热交换 (HotSwap)
     */
    @Test
    public void testHotSwap() {
        ProxySample target1 = message -> "Target1: " + message;
        ProxySample target2 = message -> "Target2: " + message;

        ProxySample proxy = ProxyBuilder.forClass(ProxySample.class)
                .withDelegate(target1)
                .enableHotswap()
                .build();

        Assert.assertEquals("Target1: test", proxy.execute("test"));

        // 执行热交换
        Swappable swappable = (Swappable) proxy;
        swappable.hotswap(target2);

        Assert.assertEquals("Target2: test", proxy.execute("test"));
    }

    /**
     * 测试场景 5：空对象模式 (Null Object)
     */
    @Test
    public void testEmptyObject() {
        ProxySample proxy = ProxyBuilder.forClass(ProxySample.class)
                .asEmptyObject()
                .build();

        // 接口方法返回 String，EmptyValueInterceptor 应返回空字符串
        Assert.assertEquals("", proxy.execute("any"));
    }

    /**
     * 测试类：用于验证类代理
     */
    public static class SampleService {
        public String hello(String name) {
            return "Service: " + name;
        }
    }
}
