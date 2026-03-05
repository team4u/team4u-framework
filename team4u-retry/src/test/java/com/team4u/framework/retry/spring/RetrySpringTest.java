package com.team4u.framework.retry.spring;

import com.team4u.framework.retry.RetryPolicy;
import com.team4u.framework.retry.backoff.Backoff;
import com.team4u.framework.retry.proxy.NamedRetryPolicy;
import com.team4u.framework.retry.proxy.RetryPolicyRegistry;
import com.team4u.framework.retry.proxy.Retryable;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.springframework.aop.framework.autoproxy.DefaultAdvisorAutoProxyCreator;
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
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(TestConfig.class)) {
            TestConfig config = context.getBean(TestConfig.class);

            // 1. 验证接口代理 (OrderService)
            OrderService orderService = context.getBean(OrderService.class);
            // 确认拿到的是代理对象（简单判断非原始实现类）
            Assert.assertNotSame(OrderServiceImpl.class, orderService.getClass());

            String result = orderService.doRetry("A100");
            Assert.assertEquals("ok_A100", result);
            // 策略是 3 次尝试，前 2 次抛异常，第 3 次成功，所以总数应该是 3
            Assert.assertEquals(3, config.orderService.count.get());

            // 2. 验证类代理 (UserService)
            UserService userService = context.getBean(UserService.class);
            Assert.assertNotSame(UserService.class, userService.getClass());

            String userResult = userService.hello("world");
            Assert.assertEquals("hello_world", userResult);
            Assert.assertEquals(3, config.userService.count.get());

        }
    }

    public interface OrderService {
        @Retryable("test-policy")
        String doRetry(String id);
    }

    @Configuration
    @EnableRetry
    public static class TestConfig {

        /**
         * 显式注册代理创建器，模拟调用方（如 Spring Boot）的环境。
         * 这比在框架配置中硬编码更具灵活性。
         */
        @Bean
        public DefaultAdvisorAutoProxyCreator defaultAdvisorAutoProxyCreator() {
            DefaultAdvisorAutoProxyCreator creator = new DefaultAdvisorAutoProxyCreator();
            creator.setProxyTargetClass(true);
            return creator;
        }

        private final OrderServiceImpl orderService = new OrderServiceImpl();
        private final UserService userService = new UserService();

        @Bean
        public OrderService orderService() {
            return orderService;
        }

        @Bean
        public UserService userService() {
            return userService;
        }
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
