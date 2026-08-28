package com.team4u.framework.retry.spring;

import com.team4u.framework.bean.provider.SpringBeanContainer;
import com.team4u.framework.bean.spring.Team4uBeanConfiguration;
import com.team4u.framework.retry.api.NamedRetryPolicyFactory;
import com.team4u.framework.retry.api.NamedRetryPolicyRegistry;
import com.team4u.framework.retry.api.RetryPolicy;
import com.team4u.framework.retry.common.backoff.Backoffs;
import com.team4u.framework.retry.proxy.Retryable;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

public class RetrySpringConfigurationTest {

    @Before
    public void setUp() {
        NamedRetryPolicyRegistry.global().unregisterAll();
        NamedRetryPolicyRegistry.global().register(new NamedRetryPolicyFactory() {
            @Override
            public String key() {
                return "task16-retry-policy";
            }

            @Override
            public RetryPolicy create() {
                return RetryPolicy.builder()
                        .maxRetries(2)
                        .backoff(Backoffs.fixed(1))
                        .build();
            }
        });
    }

    @After
    public void tearDown() {
        NamedRetryPolicyRegistry.global().unregisterAll();
    }

    @Test
    public void retryConfigurationImportsSharedBeanAdapterInsteadOfDeclaringIt() {
        Import imported = RetrySpringConfiguration.class.getAnnotation(Import.class);
        Assert.assertNotNull(imported);
        Assert.assertArrayEquals(
                new Class<?>[] {Team4uBeanConfiguration.class}, imported.value());

        for (Method method : RetrySpringConfiguration.class.getDeclaredMethods()) {
            if (Modifier.isPublic(method.getModifiers()) && method.getParameterTypes().length == 0) {
                Assert.assertNotEquals(SpringBeanContainer.class, method.getReturnType());
            }
        }
    }

    @Test
    public void enabledRetryContextHasOneBeanAdapterAndRetainsRetryBehavior() {
        AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext(TaskSixteenRetryApplication.class);
        try {
            Assert.assertEquals(1, context.getBeanNamesForType(SpringBeanContainer.class).length);

            TaskSixteenRetryService service = context.getBean(TaskSixteenRetryService.class);
            Assert.assertNotSame(TaskSixteenRetryService.class, service.getClass());
            Assert.assertEquals("task16-ok", service.call());
        } finally {
            context.close();
        }
    }

    @Configuration
    @EnableRetry
    public static class TaskSixteenRetryApplication {

        @Bean
        public TaskSixteenRetryService taskSixteenRetryService() {
            return new TaskSixteenRetryService();
        }
    }

    public static class TaskSixteenRetryService {

        private int attempts;

        @Retryable(policy = "task16-retry-policy")
        public String call() {
            if (++attempts < 3) {
                throw new IllegalStateException("task16-fail");
            }
            return "task16-ok";
        }
    }
}
