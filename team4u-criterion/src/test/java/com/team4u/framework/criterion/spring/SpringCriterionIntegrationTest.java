package com.team4u.framework.criterion.spring;

import com.team4u.framework.criterion.Criteria;
import com.team4u.framework.criterion.compiler.CompilerRegistry;
import com.team4u.framework.criterion.model.convert.ValueConverter;
import com.team4u.framework.criterion.model.convert.ValueConverterRegistry;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 表达式引擎 Spring 集成测试
 */
public class SpringCriterionIntegrationTest {

    @After
    public void cleanup() {
        // 清理全局状态，防止干扰其他测试
        CompilerRegistry.global().unregisterAll();
        ValueConverterRegistry.global().unregisterAll();
    }

    @Test
    public void testSpringBeanAutoRegistration() {
        // 创建 Spring 上下文并加载自动配置和测试配置
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(
                Team4uCriterionAutoConfiguration.class,
                TestConfig.class);

        try {
            // 验证 Criteria Bean 是否已注册
            Criteria criteria = context.getBean(Criteria.class);
            Assert.assertNotNull(criteria);

            // 验证自定义转换器是否通过 Spring 自动注册到了全局注册表中
            ValueConverterRegistry registry = ValueConverterRegistry.global();
            Assert.assertTrue("自定义转换器应该被自动注册", registry.get("mock").isPresent());

            // 验证表达式匹配是否使用了新注册的转换器
            // it:mock > 100 -> 将 "100" 转换为 MockComparable(100)，然后与 100 比较
            Assert.assertTrue(criteria.matches("it:mock == 100", 100));
        } finally {
            context.close();
        }
    }

    @Configuration
    static class TestConfig {

        /**
         * 定义一个自定义转换器 Bean
         */
        @Bean
        public ValueConverter mockConverter() {
            return new ValueConverter() {
                @Override
                public String key() {
                    return "mock";
                }

                @Override
                public Comparable<?> apply(Object obj) {
                    return new MockComparable(obj);
                }
            };
        }
    }

    static class MockComparable implements Comparable<Object> {
        private final Object val;

        MockComparable(Object val) {
            this.val = val;
        }

        @Override
        public int compareTo(Object o) {
            if (o instanceof MockComparable) {
                return String.valueOf(val).compareTo(String.valueOf(((MockComparable) o).val));
            }
            return String.valueOf(val).compareTo(String.valueOf(o));
        }
    }
}
