package com.team4u.framework.retry.proxy;

import com.team4u.framework.proxy.ProxyBuilder;
import com.team4u.framework.retry.common.backoff.Backoffs;
import com.team4u.framework.retry.inline.DefaultInlineRetryClient;
import com.team4u.framework.retry.api.RetryPolicy;
import com.team4u.framework.retry.api.NamedRetryPolicyFactory;
import com.team4u.framework.retry.api.NamedRetryPolicyRegistry;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

public class RetryInterceptorResolutionTest {

    @Before
    public void setUp() {
        NamedRetryPolicyRegistry.global().unregisterAll();
        NamedRetryPolicyRegistry.global().register(new NamedRetryPolicyFactory() {
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

    @SuppressWarnings("unchecked")
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

    @Test
    public void testRetryInterceptorRequiresInlineClient() {
        try {
            new RetryInterceptor(null, null);
            Assert.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException ex) {
            Assert.assertTrue(ex.getMessage().contains("InlineRetryClient"));
        }
    }

    @Test
    public void testRetryInterceptorKeepsPublicApiRequiringClients() {
        // 公开构造仍要求显式传入 InlineRetryClient，避免无参构造导致 NPE；
        // protected 空构造仅供延迟初始化子类（如 Spring 适配壳）使用
        for (Constructor<?> constructor : RetryInterceptor.class.getConstructors()) {
            Assert.assertTrue(constructor.getParameterCount() > 0);
        }
    }

    @Test
    public void testUninitializedInterceptorFailsFastOnInvocation() {
        // 延迟初始化子类未调用 initializeDelegate 前首次拦截应快速失败，
        // 而不是在空 delegate 上 NPE
        RetryInterceptor uninitialized = new RetryInterceptor() {
        };
        try {
            uninitialized.invoke(new com.team4u.framework.proxy.core.MethodInvocation() {
                @Override
                public Object getProxy() {
                    return null;
                }

                @Override
                public Object getTarget() {
                    return null;
                }

                @Override
                public Method getMethod() {
                    return Object.class.getMethods()[0];
                }

                @Override
                public Object[] getArguments() {
                    return new Object[0];
                }

                @Override
                public Object proceed() {
                    return null;
                }
            });
            Assert.fail("expected IllegalStateException");
        } catch (IllegalStateException ex) {
            Assert.assertTrue(ex.getMessage().contains("RetryDelegate has not been initialized"));
        } catch (Throwable t) {
            Assert.fail("expected IllegalStateException, but got " + t);
        }
    }

    @Test
    public void testResolverUsesImplementationClassAsRecoveryTarget() throws Exception {
        Method invocationMethod = ImplAnnotatedService.class.getMethod("call", String.class);

        RetryMethodResolver.ResolvedRetryMethod resolved =
                RetryMethodResolver.resolve(invocationMethod, ImplAnnotatedServiceImpl.class);

        Assert.assertEquals(ImplAnnotatedServiceImpl.class, resolved.getEffectiveMethod().getDeclaringClass());
        Assert.assertEquals(ImplAnnotatedServiceImpl.class, resolved.getRecoveryTargetType());
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
