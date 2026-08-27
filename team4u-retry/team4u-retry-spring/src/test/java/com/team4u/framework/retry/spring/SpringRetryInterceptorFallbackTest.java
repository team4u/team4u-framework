package com.team4u.framework.retry.spring;

import com.team4u.framework.retry.api.NamedRetryPolicyFactory;
import com.team4u.framework.retry.api.NamedRetryPolicyRegistry;
import com.team4u.framework.retry.api.RetryPolicy;
import com.team4u.framework.retry.common.backoff.Backoffs;
import com.team4u.framework.retry.common.concurrent.RetryExecutorManager;
import com.team4u.framework.retry.proxy.Retryable;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

public class SpringRetryInterceptorFallbackTest {

    private RetryExecutorManager executorManager;

    @Before
    public void setUp() {
        NamedRetryPolicyRegistry.global().unregisterAll();
        NamedRetryPolicyRegistry.global().register(new NamedRetryPolicyFactory() {
            @Override
            public String key() {
                return "fallback-policy";
            }

            @Override
            public RetryPolicy create() {
                return RetryPolicy.builder()
                        .maxRetries(1)
                        .backoff(Backoffs.fixed(1))
                        .build();
            }
        });
        executorManager = new RetryExecutorManager(false);
    }

    @After
    public void tearDown() {
        executorManager.shutdown();
        NamedRetryPolicyRegistry.global().unregisterAll();
    }

    @Test
    public void inlineInvocationUsesDefaultClientWhenNoBeansExist() {
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        SpringRetryInterceptor interceptor = new SpringRetryInterceptor(
                beanFactory, beanFactory, executorManager);

        ProxyFactory proxyFactory = new ProxyFactory(new InlineService());
        proxyFactory.addAdvice(interceptor);
        InlineService service = (InlineService) proxyFactory.getProxy();

        Assert.assertEquals("inline-value", service.call("value"));
    }

    public static class InlineService {
        @Retryable(policy = "fallback-policy")
        public String call(String value) {
            return "inline-" + value;
        }
    }
}
