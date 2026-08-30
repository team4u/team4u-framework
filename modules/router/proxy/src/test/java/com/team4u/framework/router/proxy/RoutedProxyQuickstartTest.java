package com.team4u.framework.router.proxy;

import com.team4u.framework.bean.BeanManager;
import com.team4u.framework.config.core.ConfigManager;
import com.team4u.framework.config.core.spi.InMemoryConfigSource;
import com.team4u.framework.router.RoutingManager;
import com.team4u.framework.router.RouterBootstrap;
import com.team4u.framework.router.proxy.annotation.RouteContext;
import com.team4u.framework.router.proxy.annotation.Routed;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

/**
 * Declarative router proxy quickstart.
 * <p>
 * Bean registrations use the JVM-local BeanManager container and quickstart-specific names,
 * so they cannot collide with application beans registered under business names.
 * </p>
 */
public class RoutedProxyQuickstartTest {

    private InMemoryConfigSource configSource;
    private ConfigManager configManager;
    private RoutingManager routingManager;
    @Before
    public void setUp() {
        resetRouterGlobals();

        configSource = new InMemoryConfigSource("routed-proxy-quickstart", 0);
        configManager = ConfigManager.builder()
                .addSource(configSource)
                .addWatcher(configSource)
                .debounceWindow(0)
                .build();
        routingManager = RoutingManager.builder()
                .configManager(configManager)
                .build();
        RoutingManager.setGlobal(routingManager);

        configSource.putAndRefresh("router.quick.delegate",
                "{\"type\":\"map\",\"rules\":[{\"condition\":\"A\",\"value\":\"quickA\"},"
                        + "{\"condition\":\"B\",\"value\":\"quickB\"}],"
                        + "\"fallbackValue\":\"quickDefault\"}");
        configSource.putAndRefresh("router.quick.vip",
                "{\"type\":\"expression\",\"rules\":[{\"condition\":\"vip == true\",\"value\":\"quickVip\"}]}");
        configSource.putAndRefresh("router.quick.standard",
                "{\"type\":\"expression\",\"rules\":[{\"condition\":\"vip == false\",\"value\":\"quickStandard\"}]}");

        BeanManager.getInstance().registerBean("quickA", new QuickDelegateServiceA());
        BeanManager.getInstance().registerBean("quickB", new QuickDelegateServiceB());
        BeanManager.getInstance().registerBean("quickDefault", new QuickDelegateServiceDefault());
        BeanManager.getInstance().registerBean("quickVip", new QuickDynamicVipService());
        BeanManager.getInstance().registerBean("quickStandard", new QuickDynamicStandardService());
    }
    @After
    public void tearDown() {
        // registerBean uses BeanManager's JVM-local container; unique quickstart names
        // make those registrations deterministic when tests share one JVM.
        resetRouterGlobals();
    }

    @Test
    public void routedProxyUsesAnnotationsBeanResolutionAndDelegateResult() {
        DelegateService proxy = RoutedProxyFactory.createProxy(
                DelegateService.class, routingManager);

        Assert.assertTrue(Proxy.isProxyClass(proxy.getClass()));
        Assert.assertEquals("A:A", proxy.invoke("A"));
        Assert.assertEquals("B:B", proxy.invoke("B"));
        Assert.assertEquals("DEFAULT:unknown", proxy.invoke("unknown"));
    }

    @Test
    public void dynamicRouterIdRendersRouteContextProperties() {
        DynamicService proxy = RoutedProxyFactory.createProxy(
                DynamicService.class, routingManager);

        Request vip = new Request("vip", true);
        Assert.assertEquals("VIP:vip", proxy.invoke(vip));

        Request standard = new Request("standard", false);
        Assert.assertEquals("STANDARD:standard", proxy.invoke(standard));
    }

    @Test
    public void beanManagerResolutionCanBeCustomized() {
        CustomService proxy = RoutedProxyFactory.createProxy(
                CustomService.class,
                routingManager,
                beanName -> (CustomService) tenant -> "custom:" + beanName + ":" + tenant);

        Assert.assertEquals("custom:quickA:A", proxy.invoke("A"));
    }

    private static void resetRouterGlobals() {
        RoutingManager.resetGlobalForTest();
        RouterBootstrap.global().resetForTest();
    }

    public static class Request {
        private final String tier;
        private final boolean vip;

        public Request(String tier, boolean vip) {
            this.tier = tier;
            this.vip = vip;
        }

        public String getTier() {
            return tier;
        }

        public boolean isVip() {
            return vip;
        }
    }

    @Routed(routerId = "quick.delegate")
    public interface DelegateService {
        String invoke(@RouteContext String tenant);
    }

    @Routed(routerId = "quick.${tier}")
    public interface DynamicService {
        String invoke(@RouteContext Request request);
    }

    @Routed(routerId = "quick.delegate")
    public interface CustomService {
        String invoke(@RouteContext String tenant);
    }

    private static final class QuickDelegateServiceA implements DelegateService {
        @Override
        public String invoke(String tenant) {
            return "A:" + tenant;
        }
    }

    private static final class QuickDelegateServiceB implements DelegateService {
        @Override
        public String invoke(String tenant) {
            return "B:" + tenant;
        }
    }

    private static final class QuickDelegateServiceDefault implements DelegateService {
        @Override
        public String invoke(String tenant) {
            return "DEFAULT:" + tenant;
        }
    }

    private static final class QuickDynamicVipService implements DynamicService {
        @Override
        public String invoke(Request request) {
            return "VIP:" + request.getTier();
        }
    }

    private static final class QuickDynamicStandardService implements DynamicService {
        @Override
        public String invoke(Request request) {
            return "STANDARD:" + request.getTier();
        }
    }
}
