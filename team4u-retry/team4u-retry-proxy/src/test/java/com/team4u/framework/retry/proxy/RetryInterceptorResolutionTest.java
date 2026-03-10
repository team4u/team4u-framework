package com.team4u.framework.retry.proxy;

import com.team4u.framework.proxy.ProxyBuilder;
import com.team4u.framework.retry.backoff.Backoffs;
import com.team4u.framework.retry.client.DefaultInlineRetryClient;
import com.team4u.framework.retry.policy.RetryPolicy;
import com.team4u.framework.retry.policy.RetryPolicyFactory;
import com.team4u.framework.retry.policy.RetryPolicyFactoryRegistry;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

public class RetryInterceptorResolutionTest {

    @Before
    public void setUp() {
        RetryPolicyFactoryRegistry.global().unregisterAll();
        RetryPolicyFactoryRegistry.global().register(new RetryPolicyFactory() {
            @Override
            public String key() {
                return "resolution-policy";
            }

            @Override
            public RetryPolicy create() {
                return RetryPolicy.builder()
                        .maxRetries(2)
                        .backoff(Backoffs.fixed(0L))
                        .retryOn(RuntimeException.class)
                        .build();
            }
        });
    }

    @Test
    public void testProxyResolvesAnnotationFromImplementationMethod() {
        ImplAnnotatedServiceImpl target = new ImplAnnotatedServiceImpl();
        ImplAnnotatedService proxy = ProxyBuilder.forClass(ImplAnnotatedService.class)
                .delegate(target)
                .intercept(new RetryInterceptor(DefaultInlineRetryClient.getInstance(), null))
                .build();

        Assert.assertEquals("impl-A100", proxy.call("A100"));
        Assert.assertEquals(3, target.count.get());
    }

    @Test
    public void testProxyResolvesBridgeMethodToUserMethod() {
        GenericStringService target = new GenericStringService();
        GenericService<String> proxy = ProxyBuilder.forClass(GenericService.class)
                .delegate(target)
                .intercept(new RetryInterceptor(DefaultInlineRetryClient.getInstance(), null))
                .build();

        Assert.assertEquals("bridge-B200", proxy.call("B200"));
        Assert.assertEquals(3, target.count.get());
    }

    @Test
    public void testProxyResolvesClassLevelAnnotation() {
        ClassAnnotatedProxyService target = new ClassAnnotatedProxyService();
        ClassAnnotatedProxyService proxy = ProxyBuilder.forClass(ClassAnnotatedProxyService.class)
                .delegate(target)
                .intercept(new RetryInterceptor(DefaultInlineRetryClient.getInstance(), null))
                .build();

        Assert.assertEquals("class-C300", proxy.call("C300"));
        Assert.assertEquals(3, target.count.get());
    }

    public interface ImplAnnotatedService {
        String call(String id);
    }

    public interface GenericService<T> {
        T call(T value);
    }

    public static class ImplAnnotatedServiceImpl implements ImplAnnotatedService {
        private final AtomicInteger count = new AtomicInteger();

        @Override
        @Retryable(policy = "resolution-policy")
        public String call(String id) {
            if (count.incrementAndGet() < 3) {
                throw new RuntimeException("fail");
            }
            return "impl-" + id;
        }
    }

    public static class GenericStringService implements GenericService<String> {
        private final AtomicInteger count = new AtomicInteger();

        @Override
        @Retryable(policy = "resolution-policy")
        public String call(String value) {
            if (count.incrementAndGet() < 3) {
                throw new RuntimeException("fail");
            }
            return "bridge-" + value;
        }
    }

    @Retryable(policy = "resolution-policy")
    public static class ClassAnnotatedProxyService {
        private final AtomicInteger count = new AtomicInteger();

        public String call(String value) {
            if (count.incrementAndGet() < 3) {
                throw new RuntimeException("fail");
            }
            return "class-" + value;
        }
    }
}
