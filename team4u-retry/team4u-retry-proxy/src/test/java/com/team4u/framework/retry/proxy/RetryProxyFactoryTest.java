package com.team4u.framework.retry.proxy;

import com.team4u.framework.proxy.core.ProxyException;
import com.team4u.framework.retry.backoff.Backoffs;
import com.team4u.framework.retry.client.DefaultInlineRetryClient;
import com.team4u.framework.retry.policy.RetryPolicy;
import com.team4u.framework.retry.policy.RetryPolicyFactory;
import com.team4u.framework.retry.policy.RetryPolicyFactoryRegistry;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

public class RetryProxyFactoryTest {

    @Before
    public void setUp() {
        RetryPolicyFactoryRegistry.global().unregisterAll();
        RetryPolicyFactoryRegistry.global().register(new RetryPolicyFactory() {
            @Override
            public String key() {
                return "proxy-factory-policy";
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
        FinalMethodService.invocations.set(0);
    }

    @Test
    public void testCreateProxyWithExplicitInterfaceType() {
        InterfaceServiceImpl target = new InterfaceServiceImpl();

        InterfaceService proxy = RetryProxyFactory.createProxy(
                target,
                InterfaceService.class,
                DefaultInlineRetryClient.getInstance(),
                null);

        Assert.assertEquals("iface-A100", proxy.call("A100"));
        Assert.assertEquals(3, target.count.get());
    }

    @Test
    public void testCreateProxyWithExplicitClassType() {
        ClassService target = new ClassService();

        ClassService proxy = RetryProxyFactory.createProxy(
                target,
                ClassService.class,
                DefaultInlineRetryClient.getInstance(),
                null);

        Assert.assertEquals("class-B200", proxy.call("B200"));
        Assert.assertEquals(3, target.count.get());
    }

    @Test
    public void testCreateProxyRejectsFinalClass() {
        try {
            RetryProxyFactory.createProxy(
                    new FinalService(),
                    FinalService.class,
                    DefaultInlineRetryClient.getInstance(),
                    null);
            Assert.fail("expected ProxyException");
        } catch (ProxyException ex) {
            Assert.assertTrue(ex.getMessage().contains("No suitable proxy engine"));
        }
    }

    @Test
    public void testFinalRetryableMethodIsNotIntercepted() {
        FinalMethodService target = new FinalMethodService();
        FinalMethodService proxy = RetryProxyFactory.createProxy(
                target,
                FinalMethodService.class,
                DefaultInlineRetryClient.getInstance(),
                null);

        try {
            proxy.call("C300");
            Assert.fail("expected RuntimeException");
        } catch (RuntimeException ex) {
            Assert.assertEquals("fail", ex.getMessage());
        }

        Assert.assertEquals(1, FinalMethodService.invocations.get());
    }

    public interface InterfaceService {
        String call(String id);
    }

    public static class InterfaceServiceImpl implements InterfaceService {
        private final AtomicInteger count = new AtomicInteger();

        @Override
        @Retryable(policy = "proxy-factory-policy")
        public String call(String id) {
            if (count.incrementAndGet() < 3) {
                throw new RuntimeException("fail");
            }
            return "iface-" + id;
        }
    }

    public static class ClassService {
        private final AtomicInteger count = new AtomicInteger();

        @Retryable(policy = "proxy-factory-policy")
        public String call(String id) {
            if (count.incrementAndGet() < 3) {
                throw new RuntimeException("fail");
            }
            return "class-" + id;
        }
    }

    public static final class FinalService {
        public String call(String id) {
            return id;
        }
    }

    public static class FinalMethodService {
        private static final AtomicInteger invocations = new AtomicInteger();

        @Retryable(policy = "proxy-factory-policy")
        public final void call(String id) {
            invocations.incrementAndGet();
            throw new RuntimeException("fail");
        }
    }
}
