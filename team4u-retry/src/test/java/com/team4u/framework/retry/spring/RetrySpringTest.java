package com.team4u.framework.retry.spring;

import com.team4u.framework.retry.RetryBackend;
import com.team4u.framework.retry.RetryPolicy;
import com.team4u.framework.retry.backoff.Backoff;
import com.team4u.framework.retry.proxy.NamedRetryPolicy;
import com.team4u.framework.retry.proxy.RetryPolicyRegistry;
import com.team4u.framework.retry.proxy.Retryable;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Spring 自动代理重试单元测试
 *
 * @author jay.wu
 */
public class RetrySpringTest {

    @Before
    public void setup() {
        // 清理全局策略，防止测试间干扰
        RetryPolicyRegistry.global().unregisterAll();
        RetryPolicyRegistry.global().register(new NamedRetryPolicy() {
            @Override
            public String key() {
                return "test-policy";
            }

            @Override
            public RetryPolicy getPolicy() {
                return RetryPolicy.builder()
                        .totalAttempts(3)
                        .backoff(Backoff.fixed(1))
                        .build();
            }
        });
    }

    @Test
    public void testSpringAutoProxy() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(TestConfig.class);
        try {
            TestConfig config = context.getBean(TestConfig.class);

            // 1. 验证接口代理 (OrderService)
            OrderService orderService = context.getBean(OrderService.class);
            // 确认拿到的是代理对象（简单判断非原始实现类）
            Assert.assertNotSame(OrderServiceImpl.class, orderService.getClass());

            String result = orderService.doRetry("A100");
            Assert.assertEquals("ok_A100", result);
            // 策略是 3 次尝试，前 2 次抛异常，第 3 次成功，所以总数应该是 3
            Assert.assertEquals(3, config.orderServiceImpl.count.get());

            // 2. 验证类代理 (UserService)
            UserService userService = context.getBean(UserService.class);
            Assert.assertNotSame(UserService.class, userService.getClass());

            String userResult = userService.hello("world");
            Assert.assertEquals("hello_world", userResult);
            Assert.assertEquals(3, config.userService.count.get());

        } finally {
            context.close();
        }
    }

    @Configuration
    @EnableRetry
    public static class TestConfig {

        private final OrderServiceImpl orderServiceImpl = new OrderServiceImpl();
        private final UserService userService = new UserService();

        @Bean
        public RetryBackend mockBackend() {
            return new RetryBackend() {
                @Override
                public String saveIntent(String taskType, String payload) {
                    return "id";
                }

                @Override
                public void completeIntent(String intentId) {
                }

                @Override
                public void submitForDelay(String intentId, String taskType, String payload, long delay) {
                }
            };
        }

        @Bean
        public OrderService orderServiceImpl() {
            return orderServiceImpl;
        }

        @Bean
        public UserService userService() {
            return userService;
        }
    }

    public interface OrderService {
        @Retryable("test-policy")
        String doRetry(String id);
    }

    public static class OrderServiceImpl implements OrderService {
        public AtomicInteger count = new AtomicInteger();

        @Override
        public String doRetry(String id) {
            if (count.incrementAndGet() < 3) {
                throw new RuntimeException("fail");
            }
            return "ok_" + id;
        }
    }

    /**
     * 测试普通类代理（无接口）
     */
    @Service
    public static class UserService {
        public AtomicInteger count = new AtomicInteger();

        @Retryable("test-policy")
        public String hello(String name) {
            if (count.incrementAndGet() < 3) {
                throw new RuntimeException("fail");
            }
            return "hello_" + name;
        }
    }
}
