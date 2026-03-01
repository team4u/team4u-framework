package com.team4u.framework.router.proxy;

import com.team4u.framework.bean.BeanManager;
import com.team4u.framework.config.test.TestConfigContext;
import com.team4u.framework.router.RoutingManager;
import com.team4u.framework.router.api.exception.RouteNotFoundException;
import com.team4u.framework.router.proxy.annotation.RouteContext;
import com.team4u.framework.router.proxy.annotation.Routed;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * 路由代理测试类
 */
public class RoutedProxyTest {

    private TestConfigContext configContext;
    private RoutingManager routingManager;

    @Before
    public void setup() {
        // 1. 注册测试用的 implementation bean
        BeanManager.getInstance().registerBean("serviceA", new ServiceA());
        BeanManager.getInstance().registerBean("serviceB", new ServiceB());

        // 2. 初始化本地配置管理测试上下文
        configContext = TestConfigContext.create();

        // 3. 构建局部 RoutingManager
        routingManager = RoutingManager.builder()
                .configManager(configContext.getManager())
                .build();
    }

    @Test
    public void testRoutedBeanLocator() {
        // 配置路由规则
        configContext.put("router.test_router",
                "{\"type\":\"map\",\"rules\":[{\"condition\":\"A\",\"value\":\"serviceA\"},{\"condition\":\"B\",\"value\":\"serviceB\"}]}");

        // 测试定位 A
        TestService serviceA = RoutedBeanLocator.locate(routingManager, "test_router", "A", TestService.class);
        Assert.assertTrue(serviceA instanceof ServiceA);
        Assert.assertEquals("A", serviceA.sayHello("context"));

        // 测试定位 B
        TestService serviceB = RoutedBeanLocator.locate(routingManager, "test_router", "B", TestService.class);
        Assert.assertTrue(serviceB instanceof ServiceB);
        Assert.assertEquals("B", serviceB.sayHello("context"));
    }

    @Test
    public void testRoutedProxy() {
        // 配置路由规则
        // team4u-criterion 中 "it" 代表根对象（完整名：SUBJECT_IT = "it"）
        configContext.put("router.test_proxy_router",
                "{\"type\":\"expression\",\"rules\":[{\"condition\":\"it == 'A'\",\"value\":\"serviceA\"}],\"fallbackValue\":\"serviceB\"}");

        // 创建代理，显式传入 routingManager
        TestService proxy = RoutedProxyFactory.createProxy(TestService.class, routingManager);

        // 调用代理，期望路由到 serviceA
        Assert.assertEquals("A", proxy.sayHello("A"));

        // 调用代理，期望走 fallback 路由到 serviceB
        Assert.assertEquals("B", proxy.sayHello("otherValue"));
    }

    @Test(expected = RouteNotFoundException.class)
    public void testRouteUnmatch() {
        // 配置无兜底的路由规则
        configContext.put("router.unmatch_router",
                "{\"type\":\"map\",\"rules\":[{\"condition\":\"A\",\"value\":\"serviceA\"}]}");
        // 请求 C，无法匹配，期望抛出 IllegalStateException
        RoutedBeanLocator.locate(routingManager, "unmatch_router", "C", TestService.class);
    }

    @Test
    public void testRoutedProxyWithSimpleType() {
        // 使用 map 类型，条件 123 对应 serviceA
        configContext.put("router.router_123",
                "{\"type\":\"map\",\"rules\":[{\"condition\":\"123\",\"value\":\"serviceA\"}]}");

        // 创建代理
        SimpleTypeService proxy = RoutedProxyFactory.createProxy(SimpleTypeService.class, routingManager);

        // 调用代理，routerId = router_123，context = 123
        // 路由成功则返回 A
        Assert.assertEquals("A", proxy.sayHello(123));
    }

    @Test
    public void testRoutedProxyWithStringDynamic() {
        // 配置路由规则：user_abc 对应 serviceA
        configContext.put("router.user_abc",
                "{\"type\":\"map\",\"rules\":[{\"condition\":\"abc\",\"value\":\"serviceA\"}]}");

        // 创建代理
        UserTypeService proxy = RoutedProxyFactory.createProxy(UserTypeService.class, routingManager);

        // 调用代理，userId = "abc"，routerId = "user_abc"
        Assert.assertEquals("A", proxy.sayHello("abc"));
    }

    @Routed(routerId = "test_proxy_router")
    public interface TestService {
        String sayHello(@RouteContext String request);
    }

    /**
     * 测试通过基本类型进行路由
     */
    @Routed(routerId = "router_${id}")
    public interface SimpleTypeService {
        String sayHello(@RouteContext int id);
    }

    /**
     * 测试通过 String 类型进行动态路由
     */
    @Routed(routerId = "user_${userId}")
    public interface UserTypeService {
        String sayHello(@RouteContext String userId);
    }

    public static class ServiceA implements TestService, SimpleTypeService, UserTypeService {
        @Override
        public String sayHello(String request) {
            return "A";
        }

        @Override
        public String sayHello(int id) {
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
