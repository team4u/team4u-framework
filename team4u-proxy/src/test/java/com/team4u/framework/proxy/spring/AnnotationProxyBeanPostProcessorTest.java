package com.team4u.framework.proxy.spring;

import com.team4u.framework.proxy.core.MethodInterceptor;
import com.team4u.framework.proxy.core.MethodInvocation;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * {@link AnnotationProxyBeanPostProcessor} 最小 Spring 上下文测试：
 * 注解 Bean 自动代理（含注解只在实现类的 JDK 接口场景）、无注解 Bean 原样返回、
 * final 类 warn 跳过、既有 AOP 代理跳过、FactoryBean 本体跳过
 *
 * @author jay.wu
 */
public class AnnotationProxyBeanPostProcessorTest {

    private AnnotationConfigApplicationContext context;

    @Before
    public void setUp() {
        context = new AnnotationConfigApplicationContext(TestApp.class);
    }

    @After
    public void tearDown() {
        if (context != null) {
            context.close();
        }
    }

    /**
     * 注解只在实现方法上（接口 BareService 未标注）：Bean 应被代理包装，
     * 拦截器经 targetClass 命中注解，未注解方法直通
     */
    @Test
    public void annotatedBeanIsProxiedAndIntercepted() {
        BareService service = context.getBean(BareService.class);

        assertNotSame("含注解方法的 Bean 应被代理包装", CountingServiceImpl.class, service.getClass());
        assertTrue("JDK 代理场景按接口类型可注入", BareService.class.isAssignableFrom(service.getClass()));

        assertEquals("trace-1", service.computed("a"));
        assertEquals("注解方法每次调用都被拦截增强", "trace-2", service.computed("a"));
        assertNull("未注解方法直通不受影响", service.plain());
        assertEquals("拒绝边界不越过直通方法", 2, context.getBean(TestApp.class).impl.count.get());
    }

    /**
     * 注解只标注在接口方法上：实现类 Bean 同样被代理并命中注解
     */
    @Test
    public void interfaceAnnotatedBeanIsProxied() {
        IfaceAnnotatedService service = context.getBean(IfaceAnnotatedService.class);

        assertNotSame(IfaceAnnotatedImpl.class, service.getClass());
        assertEquals("trace-1", service.run());
    }

    /**
     * 无注解 Bean 原样返回，不被代理
     */
    @Test
    public void plainBeanNotProxied() {
        PlainService service = context.getBean(PlainService.class);

        assertSame("无注解 Bean 不应被包装", PlainService.class, service.getClass());
        assertEquals("raw", service.run());
    }

    /**
     * final 类无法代理：默认策略 warn 并跳过，返回原 Bean，不阻断启动
     */
    @Test
    public void finalClassSkippedWithWarn() {
        FinalService service = context.getBean(FinalService.class);

        assertSame("final 类应跳过代理返回原 Bean", FinalService.class, service.getClass());
        assertEquals("raw-1", service.computed());
        assertEquals("final 类方法不受拦截影响", "raw-2", service.computed());
        assertEquals(2, context.getBean(TestApp.class).finalService.count.get());
    }

    /**
     * 已是 Spring AOP 代理的 Bean 跳过，避免双层代理
     */
    @Test
    public void existingAopProxySkipped() {
        Object service = context.getBean("aopProxiedService");

        // AOP 代理保持 Spring 代理类型；后置处理器不得再包一层 team4u 代理
        assertNotSame("既有 Spring AOP 代理不应被二次包装", PlainServiceImpl.class, service.getClass());
        assertEquals("raw", ((PlainServiceApi) service).run());
    }

    /**
     * FactoryBean 本体跳过：注入的是其产品（本测试产品无注解，应原样返回）
     */
    @Test
    public void factoryBeanItselfSkipped() {
        Object product = context.getBean("plainFactoryBean");

        assertEquals("factory-product", product);
    }

    // ------------------------------------------------- 被测处理器（最小子类实现）

    /**
     * 测试注解
     */
    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @Documented
    public @interface Counted {

        String value() default "";
    }

    /**
     * 最小子类：只提供注解类型 + 拦截器工厂
     */
    public static class CountedBeanPostProcessor extends AnnotationProxyBeanPostProcessor<Counted> {

        @Override
        protected Class<Counted> getAnnotationType() {
            return Counted.class;
        }

        @Override
        protected MethodInterceptor createInterceptor(Counted annotation) {
            return new MethodInterceptor() {
                @Override
                public Object invoke(MethodInvocation invocation) throws Throwable {
                    Method method = invocation.getMethod();
                    Class<?> targetClass = invocation.getTarget() == null
                            ? method.getDeclaringClass() : invocation.getTarget().getClass();
                    if (getResolver().resolve(method, targetClass) == null) {
                        return invocation.proceed();
                    }
                    Object result = invocation.proceed();
                    return "trace-" + result;
                }
            };
        }
    }

    // ------------------------------------------------- 测试装配

    @Configuration
    public static class TestApp {

        final CountingServiceImpl impl = new CountingServiceImpl();
        final IfaceAnnotatedImpl ifaceImpl = new IfaceAnnotatedImpl();
        final PlainService plainService = new PlainService();
        final FinalService finalService = new FinalService();
        final PlainServiceImpl aopTarget = new PlainServiceImpl();

        @Bean
        public static CountedBeanPostProcessor countedBeanPostProcessor() {
            return new CountedBeanPostProcessor();
        }

        @Bean
        public BareService countingService() {
            return impl;
        }

        @Bean
        public IfaceAnnotatedService ifaceAnnotatedService() {
            return ifaceImpl;
        }

        @Bean
        public PlainService plainService() {
            return plainService;
        }

        @Bean
        public FinalService finalService() {
            return finalService;
        }

        @Bean
        public PlainServiceApi aopProxiedService() {
            // 既有 Spring AOP 代理：后置处理器应跳过（isAopProxy 防御）
            ProxyFactory factory = new ProxyFactory(aopTarget);
            factory.setInterfaces(PlainServiceApi.class);
            return (PlainServiceApi) factory.getProxy();
        }

        @Bean
        public FactoryBean<Object> plainFactoryBean() {
            return new FactoryBean<Object>() {
                @Override
                public Object getObject() {
                    return "factory-product";
                }

                @Override
                public Class<?> getObjectType() {
                    return String.class;
                }

                @Override
                public boolean isSingleton() {
                    return true;
                }
            };
        }
    }

    // ------------------------------------------------- 测试类型定义

    public interface BareService {

        String computed(String input);

        String plain();
    }

    /**
     * 注解只在实现方法（接口未标注）：JDK 接口代理场景的靶心用例
     */
    public static class CountingServiceImpl implements BareService {

        final AtomicInteger count = new AtomicInteger();

        @Counted
        @Override
        public String computed(String input) {
            return String.valueOf(count.incrementAndGet());
        }

        @Override
        public String plain() {
            return null;
        }
    }

    public interface IfaceAnnotatedService {

        @Counted
        String run();
    }

    /**
     * 注解只在接口方法：实现类自身未标注
     */
    public static class IfaceAnnotatedImpl implements IfaceAnnotatedService {

        final AtomicInteger count = new AtomicInteger();

        @Override
        public String run() {
            return String.valueOf(count.incrementAndGet());
        }
    }

    /**
     * 供既有 AOP 代理用例的接口
     */
    public interface PlainServiceApi {

        String run();
    }

    public static class PlainServiceImpl implements PlainServiceApi {

        @Override
        public String run() {
            return "raw";
        }
    }

    public static class PlainService {

        public String run() {
            return "raw";
        }
    }

    public static final class FinalService {

        final AtomicInteger count = new AtomicInteger();

        @Counted
        public String computed() {
            return "raw-" + count.incrementAndGet();
        }
    }
}
