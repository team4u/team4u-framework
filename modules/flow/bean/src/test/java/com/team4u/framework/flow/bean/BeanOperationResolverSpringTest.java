package com.team4u.framework.flow.bean;

import com.team4u.framework.bean.spring.Team4uBeanConfiguration;
import com.team4u.framework.flow.Flow;
import com.team4u.framework.flow.Local;
import com.team4u.framework.flow.api.Operation;
import com.team4u.framework.flow.api.OperationContext;
import com.team4u.framework.flow.model.Outcome;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.After;
import org.junit.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class BeanOperationResolverSpringTest {
    private static final String BEAN_NAME =
            "flowBeanSpringAdvisedOperation.617ce8c1";
    private static final String CLASS_PROXY_BEAN_NAME =
            "flowBeanSpringClassProxyOperation.617ce8c1";
    private static final AtomicInteger ADVICE_CALLS = new AtomicInteger();
    private static final AtomicInteger CLASS_PROXY_ADVICE_CALLS = new AtomicInteger();

    private AnnotationConfigApplicationContext context;

    @After
    public void closeContext() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    public void localInvokesExactSpringJdkProxyAndItsAdvice() {
        ADVICE_CALLS.set(0);
        context = new AnnotationConfigApplicationContext(SpringConfiguration.class);
        SpringOperation containerBean = context.getBean(BEAN_NAME, SpringOperation.class);
        BeanOperationResolver resolver = BeanOperationResolver.global();

        assertTrue(Proxy.isProxyClass(containerBean.getClass()));
        assertSame(containerBean, resolver.resolve(SpringOperation.class, BEAN_NAME));
        assertEquals("input:spring", Local.compile(
                Flow.step(SpringOperation.class, BEAN_NAME), resolver)
                .run("input").requireAccepted());
        assertEquals(1, ADVICE_CALLS.get());
    }

    @Test
    public void beanFlowsInvokesExactSpringClassProxyAndItsAdvice() {
        CLASS_PROXY_ADVICE_CALLS.set(0);
        context = new AnnotationConfigApplicationContext(SpringConfiguration.class);
        ClassProxyOperation containerBean = context.getBean(
                CLASS_PROXY_BEAN_NAME, ClassProxyOperation.class);
        BeanOperationResolver resolver = BeanOperationResolver.global();

        assertTrue(AopUtils.isCglibProxy(containerBean));
        assertSame(containerBean,
                resolver.resolve(ClassProxyOperation.class, CLASS_PROXY_BEAN_NAME));
        assertEquals("input:class-proxy", BeanFlows.compile(
                Flow.step(ClassProxyOperation.class, CLASS_PROXY_BEAN_NAME))
                .run("input").requireAccepted());
        assertEquals(1, CLASS_PROXY_ADVICE_CALLS.get());
    }

    public interface SpringOperation extends Operation<String, String> { }

    public static final class SpringOperationTarget implements SpringOperation {
        @Override
        public Outcome<String> execute(OperationContext context, String input) {
            return Outcome.accepted(input + ":spring");
        }
    }

    public static class ClassProxyOperation implements Operation<String, String> {
        @Override
        public Outcome<String> execute(OperationContext context, String input) {
            return Outcome.accepted(input + ":class-proxy");
        }
    }

    @Configuration
    @Import(Team4uBeanConfiguration.class)
    public static class SpringConfiguration {
        @Bean(name = BEAN_NAME)
        public SpringOperation advisedOperation() {
            ProxyFactory factory = new ProxyFactory(new SpringOperationTarget());
            factory.setInterfaces(SpringOperation.class);
            factory.addAdvice((MethodInterceptor) invocation -> {
                ADVICE_CALLS.incrementAndGet();
                return invocation.proceed();
            });
            return (SpringOperation) factory.getProxy();
        }

        @Bean(name = CLASS_PROXY_BEAN_NAME)
        public ClassProxyOperation classBasedAdvisedOperation() {
            ProxyFactory factory = new ProxyFactory(new ClassProxyOperation());
            factory.setProxyTargetClass(true);
            factory.addAdvice((MethodInterceptor) invocation -> {
                CLASS_PROXY_ADVICE_CALLS.incrementAndGet();
                return invocation.proceed();
            });
            return (ClassProxyOperation) factory.getProxy();
        }
    }
}
