package com.team4u.framework.router.proxy;

import com.team4u.framework.bean.BeanManager;
import com.team4u.framework.config.core.ConfigManager;
import com.team4u.framework.config.core.spi.InMemoryConfigSource;
import com.team4u.framework.router.RoutingManager;
import com.team4u.framework.router.annotation.RouteContext;
import com.team4u.framework.router.annotation.Routed;
import com.team4u.framework.router.util.RoutedBeanLocator;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * 路由代理测试类
 */
public class RoutedProxyTest {

    private final InMemoryConfigSource inMemoryConfigSource = new InMemoryConfigSource("test", 100);

    @Before
    public void setup() {
        // 1. 注册测试用的 implementation bean
        BeanManager.getInstance().registerBean("serviceA", new ServiceA());
        BeanManager.getInstance().registerBean("serviceB", new ServiceB());

        // 2. 初始化本地配置管理器
        // InMemoryConfigSource 同时实现了 ConfigSource 和 ConfigWatcher
        // 必须同时用 addSource + addWatcher 注册，putAndRefresh 才能触发热重载
        ConfigManager cm = ConfigManager.builder()
                .addSource(inMemoryConfigSource)
                .addWatcher(inMemoryConfigSource)
                .build();

        // 3. 构建并注入新的 RoutingManager
        RoutingManager.setGlobal(RoutingManager.builder()
                .configManager(cm)
                .build());
    }

    private void sleep() {
        try {
            // HotReloadManager 默认有 500ms 防抖延迟
            Thread.sleep(600);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    public void testRoutedBeanLocator() {
        // 配置路由规则
        inMemoryConfigSource.putAndRefresh("router.test_router",
                "{\"type\":\"map\",\"rules\":[{\"condition\":\"A\",\"value\":\"serviceA\"},{\"condition\":\"B\",\"value\":\"serviceB\"}]}");
        sleep();

        // 测试定位 A
        TestService serviceA = RoutedBeanLocator.locate("test_router", "A", TestService.class);
        Assert.assertTrue(serviceA instanceof ServiceA);
        Assert.assertEquals("A", serviceA.sayHello("context"));

        // 测试定位 B
        TestService serviceB = RoutedBeanLocator.locate("test_router", "B", TestService.class);
        Assert.assertTrue(serviceB instanceof ServiceB);
        Assert.assertEquals("B", serviceB.sayHello("context"));
    }

    @Test
    public void testRoutedProxy() {
        // 配置路由规则
        // team4u-criterion 中 "it" 代表根对象（完整名：SUBJECT_IT = "it"）
        inMemoryConfigSource.putAndRefresh("router.test_proxy_router",
                "{\"type\":\"expression\",\"rules\":[{\"condition\":\"it == 'A'\",\"value\":\"serviceA\"}],\"fallbackValue\":\"serviceB\"}");
        sleep();

        // 创建代理
        TestService proxy = RoutedProxyFactory.createProxy(TestService.class);

        // 调用代理，期望路由到 serviceA
        Assert.assertEquals("A", proxy.sayHello("A"));

        // 调用代理，期望走 fallback 路由到 serviceB
        Assert.assertEquals("B", proxy.sayHello("otherValue"));
    }

    @Test(expected = IllegalStateException.class)
    public void testRouteUnmatch() {
        // 配置无兜底的路由规则
        inMemoryConfigSource.putAndRefresh("router.unmatch_router",
                "{\"type\":\"map\",\"rules\":[{\"condition\":\"A\",\"value\":\"serviceA\"}]}");
        sleep();
        // 请求 C，无法匹配，期望抛出 IllegalStateException
        RoutedBeanLocator.locate("unmatch_router", "C", TestService.class);
    }

    @Routed(routerId = "test_proxy_router")
    public interface TestService {
        String sayHello(@RouteContext String request);
    }

    public static class ServiceA implements TestService {
        @Override
        public String sayHello(String request) {
            return "A";
        }
    }

    public static class ServiceB implements TestService {
        @Override
        public String sayHello(String request) {
            return "B";
        }
    }
}
