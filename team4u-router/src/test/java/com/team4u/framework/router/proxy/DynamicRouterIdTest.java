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
 * 验证 @Routed 注解中的 routerId 是否支持 ${property} 占位符表达式。
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
    public void testPlaceholderRouterId() {
        // 配置两个不同的路由策略
        configContext.put("router.routerA",
                "{\"type\":\"map\",\"rules\":[{\"condition\":\"A\",\"value\":\"dynamicServiceA\"}]}");
        configContext.put("router.routerB",
                "{\"type\":\"map\",\"rules\":[{\"condition\":\"B\",\"value\":\"dynamicServiceB\"}]}");

        // 创建代理
        TestService proxy = RoutedProxyFactory.createProxy(TestService.class, routingManager);

        // 1. 测试占位符路由 A：通过 ${routerName} 找到 "routerA"
        TestRequest requestA = new TestRequest();
        requestA.setRouterName("routerA");
        requestA.setTenant("A");
        Assert.assertEquals("A", proxy.sayHello(requestA));

        // 2. 测试占位符路由 B：通过 ${routerName} 找到 "routerB"
        TestRequest requestB = new TestRequest();
        requestB.setRouterName("routerB");
        requestB.setTenant("B");
        Assert.assertEquals("B", proxy.sayHello(requestB));
    }

    @Test
    public void testMixedRouterId() {
        // 配置混合模式路由策略
        configContext.put("router.biz.tenantA.router",
                "{\"type\":\"map\",\"rules\":[{\"condition\":\"A\",\"value\":\"dynamicServiceA\"}]}");

        TestService proxy = RoutedProxyFactory.createProxy(TestService.class, routingManager);

        // 占位符 + 常量混合
        TestRequest request = new TestRequest();
        request.setTenantId("tenantA");
        request.setTenant("A");
        Assert.assertEquals("A", proxy.sayHelloMixed(request));
    }

    @Test
    public void testConstantRouterId() {
        // 配置常量路由策略
        configContext.put("router.staticRouter",
                "{\"type\":\"map\",\"rules\":[{\"condition\":\"S\",\"value\":\"dynamicServiceA\"}]}");

        TestService proxy = RoutedProxyFactory.createProxy(TestService.class, routingManager);

        // 即使请求中包含 staticRouter 属性，也不会作为变量解析（因为它在注解中没用 ${}）
        TestRequest request = new TestRequest();
        request.setStaticRouter("ignore_me");
        request.setTenant("S");
        Assert.assertEquals("A", proxy.sayHelloConstant(request));
    }

    public interface TestService {
        // 带占位符：解析变量
        @Routed(routerId = "${routerName}")
        String sayHello(@RouteContext TestRequest request);

        // 混合模式：常量 + 变量
        @Routed(routerId = "biz.${tenantId}.router")
        String sayHelloMixed(@RouteContext TestRequest request);

        // 不带占位符：视为字面量常量
        @Routed(routerId = "staticRouter")
        String sayHelloConstant(@RouteContext TestRequest request);
    }

    @Data
    public static class TestRequest {
        private String routerName;
        private String tenantId;
        private String staticRouter;
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
        public String sayHelloMixed(TestRequest request) {
            return "A";
        }

        @Override
        public String sayHelloConstant(TestRequest request) {
            return "A";
        }
    }

    public static class ServiceB implements TestService {
        @Override
        public String sayHello(TestRequest request) {
            return "B";
        }

        @Override
        public String sayHelloMixed(TestRequest request) {
            return "B";
        }

        @Override
        public String sayHelloConstant(TestRequest request) {
            return "B";
        }
    }
}
