package com.team4u.framework.policy.spring;

import com.team4u.framework.policy.api.ContextPolicy;
import com.team4u.framework.policy.core.OrderedPolicyChain;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring 策略自动注册器单元测试
 *
 * @author gemini-cli
 */
public class SpringPolicyAutoRegistrarTest {

    @Test
    public void testAutoRegister() {
        // 创建 Spring 上下文并注册配置类
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(TestConfig.class)) {
            // 验证加了注解的注册表
            OrderedPolicyChain<String, TestPolicy> autoRegistry = context.getBean("autoRegistry", OrderedPolicyChain.class);
            Assert.assertEquals("加了注解的注册表应该自动注册 2 个策略", 2, autoRegistry.getPolicies().size());

            // 验证未加注解的注册表
            OrderedPolicyChain<String, TestPolicy> manualRegistry = context.getBean("manualRegistry", OrderedPolicyChain.class);
            Assert.assertEquals("未加注解的注册表应该为空", 0, manualRegistry.getPolicies().size());
        }
    }

    /**
     * 测试策略接口
     */
    interface TestPolicy extends ContextPolicy<String> {
    }

    /**
     * 测试配置类
     */
    @Configuration
    static class TestConfig {

        @Bean
        public SpringPolicyAutoRegistrar springPolicyAutoRegistrar() {
            return new SpringPolicyAutoRegistrar();
        }

        @Bean
        @PolicyAutoRegister
        public OrderedPolicyChain<String, TestPolicy> autoRegistry() {
            return new OrderedPolicyChain<>(TestPolicy.class);
        }

        @Bean
        public OrderedPolicyChain<String, TestPolicy> manualRegistry() {
            return new OrderedPolicyChain<>(TestPolicy.class);
        }

        @Bean
        public PolicyA policyA() {
            return new PolicyA();
        }

        @Bean
        public PolicyB policyB() {
            return new PolicyB();
        }
    }

    /**
     * 测试策略实现 A
     */
    static class PolicyA implements TestPolicy {
        @Override
        public boolean supports(String context) {
            return "A".equals(context);
        }

        @Override
        public int priority() {
            return 1;
        }
    }

    /**
     * 测试策略实现 B
     */
    static class PolicyB implements TestPolicy {
        @Override
        public boolean supports(String context) {
            return "B".equals(context);
        }

        @Override
        public int priority() {
            return 2;
        }
    }
}
