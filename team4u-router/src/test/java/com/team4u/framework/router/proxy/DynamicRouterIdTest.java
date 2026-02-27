package com.team4u.framework.router.proxy;

import com.team4u.framework.bean.BeanManager;
import com.team4u.framework.config.test.TestConfigContext;
import com.team4u.framework.router.RoutingManager;
import com.team4u.framework.router.proxy.annotation.RouteContext;
import com.team4u.framework.router.proxy.annotation.Routed;
import lombok.Data;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * 动态路由 ID 测试
 * <p>
 * 验证 @Routed 注解中的 routerId 是否支持属性表达式动态提取。
 * </p>
 *
 * @author jay.wu
 */
public class DynamicRouterIdTest {

    private TestConfigContext configContext;
    private RoutingManager routingManager;

    @Before
    public void setup() {
        // 使用唯一的名称避免与其他测试中的 Bean 冲突
        BeanManager.getInstance().registerBean("dynamicServiceA", new ServiceA());
        BeanManager.getInstance().registerBean("dynamicServiceB", new ServiceB());

        configContext = TestConfigContext.create();
        routingManager = RoutingManager.builder()
                .configManager(configContext.getManager())
                .build();
    }

    @Test
    public void testDynamicRouterId() {
        // 配置两个不同的路由策略
        configContext.put("router.routerA",
                "{\"type\":\"map\",\"rules\":[{\"condition\":\"A\",\"value\":\"dynamicServiceA\"}]}");
        configContext.put("router.routerB",
                "{\"type\":\"map\",\"rules\":[{\"condition\":\"B\",\"value\":\"dynamicServiceB\"}]}");

        // 创建代理
        TestService proxy = RoutedProxyFactory.createProxy(TestService.class, routingManager);

        // 1. 测试动态路由 A：通过 request.routerName 找到 "routerA"
        TestRequest requestA = new TestRequest();
        requestA.setRouterName("routerA");
        requestA.setTenant("A");
        Assert.assertEquals("A", proxy.sayHello(requestA));

        // 2. 测试动态路由 B：通过 request.routerName 找到 "routerB"
        TestRequest requestB = new TestRequest();
        requestB.setRouterName("routerB");
        requestB.setTenant("B");
        Assert.assertEquals("B", proxy.sayHello(requestB));
    }

    @Test
    public void testStaticFallback() {
        // 配置静态路由策略
        configContext.put("router.staticRouter",
                "{\"type\":\"map\",\"rules\":[{\"condition\":\"S\",\"value\":\"dynamicServiceA\"}]}");

        TestService proxy = RoutedProxyFactory.createProxy(TestService.class, routingManager);

        // 传入一个没有 'staticRouter' 属性的对象，应该回退到使用 'staticRouter' 作为字面量
        TestRequest request = new TestRequest();
        request.setTenant("S");
        Assert.assertEquals("A", proxy.sayHelloStatic(request));
    }

    public interface TestService {
        // routerId 指向 request 中的 routerName 属性
        @Routed(routerId = "routerName")
        String sayHello(@RouteContext TestRequest request);

        // routerId 是一个静态值，request 中没有该属性
        @Routed(routerId = "staticRouter")
        String sayHelloStatic(@RouteContext TestRequest request);
    }

    @Data
    public static class TestRequest {
        private String routerName;
        private String tenant;

        @Override
        public String toString() {
            return tenant;
        }
    }

    public static class ServiceA implements TestService {
        @Override
        public String sayHello(TestRequest request) {
            return "A";
        }

        @Override
        public String sayHelloStatic(TestRequest request) {
            return "A";
        }
    }

    public static class ServiceB implements TestService {
        @Override
        public String sayHello(TestRequest request) {
            return "B";
        }

        @Override
        public String sayHelloStatic(TestRequest request) {
            return "B";
        }
    }
}
