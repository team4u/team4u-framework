package com.team4u.framework.policy.spring;

import com.team4u.framework.policy.api.ContextPolicy;
import com.team4u.framework.policy.core.OrderedPolicyChain;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

/**
 * Spring 策略自动注册器单元测试
 *
 * @author jay.wu
 */
public class SpringPolicyAutoRegistrarTest {

    @Test
    public void testAutoRegister() {
        // 创建 Spring 上下文并注册配置类
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(TestConfig.class)) {
            // 验证加了注解的注册表
            OrderedPolicyChain<String, TestPolicy> autoRegistry = context.getBean("autoRegistry", OrderedPolicyChain.class);
            Assert.assertEquals("加了注解的注册表应该自动注册 2 个策略", 2, autoRegistry.getPolicies().size());

            OrderedPolicyChain<String, TestPolicy> classAnnotatedRegistry = context.getBean("classAnnotatedRegistry",
                    OrderedPolicyChain.class);
            Assert.assertEquals("标在类型上的注解也应该生效", 2, classAnnotatedRegistry.getPolicies().size());

            OrderedPolicyChain<String, TestPolicy> secondAutoRegistry = context.getBean("secondAutoRegistry",
                    OrderedPolicyChain.class);
            Assert.assertEquals("同一策略类型对应多个 registry 时都应注册", 2, secondAutoRegistry.getPolicies().size());

            // 验证未加注解的注册表
            OrderedPolicyChain<String, TestPolicy> manualRegistry = context.getBean("manualRegistry", OrderedPolicyChain.class);
            Assert.assertEquals("未加注解的注册表应该为空", 0, manualRegistry.getPolicies().size());
        }
    }

    @Test
    public void testAutoRegisterIdempotent() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(TestConfig.class)) {
            SpringPolicyAutoRegistrar registrar = context.getBean(SpringPolicyAutoRegistrar.class);
            OrderedPolicyChain<String, TestPolicy> autoRegistry = context.getBean("autoRegistry", OrderedPolicyChain.class);

            registrar.afterSingletonsInstantiated();

            Assert.assertEquals("重复触发自动注册不应重复写入", 2, autoRegistry.getPolicies().size());
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
    @ComponentScan(basePackageClasses = TypeAnnotatedRegistry.class)
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
        @PolicyAutoRegister
        public OrderedPolicyChain<String, TestPolicy> secondAutoRegistry() {
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

    @Component("classAnnotatedRegistry")
    @PolicyAutoRegister
    static class TypeAnnotatedRegistry extends OrderedPolicyChain<String, TestPolicy> {
        TypeAnnotatedRegistry() {
            super(TestPolicy.class);
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
