package com.team4u.framework.retry.spring;

import com.team4u.framework.retry.Backoff;
import com.team4u.framework.retry.RetryPolicy;
import com.team4u.framework.retry.policy.NamedRetryPolicy;
import com.team4u.framework.retry.policy.RetryPolicyRegistry;
import com.team4u.framework.retry.proxy.Retryable;
import com.team4u.framework.retry.recovery.RecoveryHandlerRegistry;
import com.team4u.framework.retry.recovery.RetryTaskTypes;
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
        RetryPolicyRegistry.global().unregisterAll();
        RecoveryHandlerRegistry.global().unregisterAll();
        RetryPolicyRegistry.global().register(new NamedRetryPolicy() {
            @Override
            public String key() {
                return "test-policy";
            }

            @Override
            public RetryPolicy getPolicy() {
                return RetryPolicy.builder()
                        .maxAttempts(3)
                        .backoff(Backoff.fixed(1))
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
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(JdkProxyConfig.class)) {
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
            Assert.assertTrue(RecoveryHandlerRegistry.global().get(RetryTaskTypes.DEFAULT_PROXY_RECOVERY).isPresent());
        }
    }

    public interface OrderService {
        @Retryable(policy = "test-policy")
        String doRetry(String id);
    }

    public interface ImplAnnotatedService {
        String call(String id);
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

    @Configuration
    @EnableRetry
    public static class JdkProxyConfig {
        private final ImplAnnotatedServiceImpl implAnnotatedService = new ImplAnnotatedServiceImpl();

        @Bean
        public ImplAnnotatedService implAnnotatedService() {
            return implAnnotatedService;
        }
    }
}
