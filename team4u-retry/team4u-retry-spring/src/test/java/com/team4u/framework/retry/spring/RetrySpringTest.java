package com.team4u.framework.retry.spring;

import com.team4u.framework.retry.backoff.Backoffs;
import com.team4u.framework.retry.client.ManagedRetryClient;
import com.team4u.framework.retry.domain.ManagedSubmitResult;
import com.team4u.framework.retry.domain.RetryTaskSpec;
import com.team4u.framework.retry.policy.RetryPolicy;
import com.team4u.framework.retry.policy.RetryPolicyFactory;
import com.team4u.framework.retry.policy.RetryPolicyFactoryRegistry;
import com.team4u.framework.retry.proxy.InvocationReplay;
import com.team4u.framework.retry.proxy.RetryMode;
import com.team4u.framework.retry.proxy.Retryable;
import com.team4u.framework.retry.recovery.RecoveryHandlerRegistry;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicInteger;

public class RetrySpringTest {

    @Before
    public void setup() {
        RetryPolicyFactoryRegistry.global().unregisterAll();
        RecoveryHandlerRegistry.global().unregisterAll();
        RetryPolicyFactoryRegistry.global().register(new RetryPolicyFactory() {
            @Override
            public String key() {
                return "test-policy";
            }

            @Override
            public RetryPolicy create() {
                return RetryPolicy.builder()
                        .maxAttempts(3)
                        .backoff(Backoffs.fixed(1))
                        .build();
            }
        });
    }

    @Test
    public void testSpringAutoProxy() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(TestConfig.class)) {
            TestConfig config = context.getBean(TestConfig.class);

            OrderService orderService = context.getBean(OrderService.class);
            Assert.assertNotSame(OrderServiceImpl.class, orderService.getClass());
            Assert.assertEquals("ok_A100", orderService.doRetry("A100"));
            Assert.assertEquals(3, config.orderService.count.get());

            UserService userService = context.getBean(UserService.class);
            Assert.assertNotSame(UserService.class, userService.getClass());
            Assert.assertEquals("hello_world", userService.hello("world"));
            Assert.assertEquals(3, config.userService.count.get());

            ClassAnnotatedService classAnnotatedService = context.getBean(ClassAnnotatedService.class);
            Assert.assertNotSame(ClassAnnotatedService.class, classAnnotatedService.getClass());
            Assert.assertEquals("class_level_C300", classAnnotatedService.call("C300"));
            Assert.assertEquals(3, config.classAnnotatedService.count.get());
        }
    }

    @Test
    public void testSpringJdkProxyShouldFindAnnotationOnImplementationMethod() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(
                JdkProxyConfig.class)) {
            JdkProxyConfig config = context.getBean(JdkProxyConfig.class);
            ImplAnnotatedService service = context.getBean(ImplAnnotatedService.class);

            Assert.assertNotSame(ImplAnnotatedServiceImpl.class, service.getClass());
            Assert.assertEquals("impl_B200", service.call("B200"));
            Assert.assertEquals(3, config.implAnnotatedService.count.get());
        }
    }

    @Test
    public void testEnableRetryShouldAutoRegisterDefaultRecoveryHandler() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(TestConfig.class)) {
            Assert.assertTrue(RecoveryHandlerRegistry.global()
                    .get(InvocationReplay.TASK_NAME).isPresent());
        }
    }

    @Test
    public void testSpringManagedMethodRejectsNonVoidReturnType() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(
                ManagedConfig.class)) {
            ManagedConfig config = context.getBean(ManagedConfig.class);
            ManagedService service = context.getBean(ManagedService.class);

            try {
                service.notifyPay("M100");
                Assert.fail("expected IllegalStateException");
            } catch (IllegalStateException ex) {
                Assert.assertTrue(ex.getMessage().contains("only supports void return types"));
            }

            Assert.assertEquals(0, config.managedClient.submitCount.get());
        }
    }

    public interface OrderService {
        @Retryable(policy = "test-policy")
        String doRetry(String id);
    }

    public interface ImplAnnotatedService {
        String call(String id);
    }

    public interface ManagedService {
        String notifyPay(String id);
    }

    @Configuration
    @EnableRetry
    public static class TestConfig {
        private final OrderServiceImpl orderService = new OrderServiceImpl();
        private final UserService userService = new UserService();
        private final ClassAnnotatedService classAnnotatedService = new ClassAnnotatedService();

        @Bean
        public OrderService orderService() {
            return orderService;
        }

        @Bean
        public UserService userService() {
            return userService;
        }

        @Bean
        public ClassAnnotatedService classAnnotatedService() {
            return classAnnotatedService;
        }
    }

    public static class OrderServiceImpl implements OrderService {
        private final AtomicInteger count = new AtomicInteger();

        @Override
        public String doRetry(String id) {
            if (count.incrementAndGet() < 3) {
                throw new RuntimeException("fail");
            }
            return "ok_" + id;
        }
    }

    @Service
    public static class UserService {
        private final AtomicInteger count = new AtomicInteger();

        @Retryable(policy = "test-policy")
        public String hello(String name) {
            if (count.incrementAndGet() < 3) {
                throw new RuntimeException("fail");
            }
            return "hello_" + name;
        }
    }

    @Retryable(policy = "test-policy")
    public static class ClassAnnotatedService {
        private final AtomicInteger count = new AtomicInteger();

        public String call(String value) {
            if (count.incrementAndGet() < 3) {
                throw new RuntimeException("fail");
            }
            return "class_level_" + value;
        }
    }

    public static class ImplAnnotatedServiceImpl implements ImplAnnotatedService {
        private final AtomicInteger count = new AtomicInteger();

        @Override
        @Retryable(policy = "test-policy")
        public String call(String id) {
            if (count.incrementAndGet() < 3) {
                throw new RuntimeException("fail");
            }
            return "impl_" + id;
        }
    }

    public static class ManagedServiceImpl implements ManagedService {
        @Override
        @Retryable(policy = "test-policy", mode = RetryMode.MANAGED)
        public String notifyPay(String id) {
            return "managed_" + id;
        }
    }

    @Configuration
    @EnableRetry
    public static class JdkProxyConfig {
        private final ImplAnnotatedServiceImpl implAnnotatedService = new ImplAnnotatedServiceImpl();

        @Bean
        public ImplAnnotatedService implAnnotatedService() {
            return implAnnotatedService;
        }
    }

    @Configuration
    @EnableRetry
    public static class ManagedConfig {
        private final ManagedRetryClientStub managedClient = new ManagedRetryClientStub();
        private final ManagedServiceImpl managedService = new ManagedServiceImpl();

        @Bean
        public ManagedRetryClient managedRetryClient() {
            return managedClient;
        }

        @Bean
        public ManagedService managedService() {
            return managedService;
        }
    }

    private static class ManagedRetryClientStub implements ManagedRetryClient {
        private final AtomicInteger submitCount = new AtomicInteger();

        @Override
        public <T> ManagedSubmitResult<T> submit(RetryTaskSpec<T> spec) {
            submitCount.incrementAndGet();
            return new ManagedSubmitResult.Accepted<T>("task-1", "SCHEDULED", null);
        }
    }
}
