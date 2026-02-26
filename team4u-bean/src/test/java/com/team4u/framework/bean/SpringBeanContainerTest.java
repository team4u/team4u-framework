package com.team4u.framework.bean;

import com.team4u.framework.bean.provider.SpringBeanContainer;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring 桥接功能测试
 *
 * @author jay.wu
 */
public class SpringBeanContainerTest {

    @Test
    public void testSpringIntegration() {
        // 1. 初始化 Spring 上下文
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(TestConfig.class);

        // 2. 验证 SpringBeanContainer 是否已自动注册到 BeanManager
        BeanManager manager = BeanManager.getInstance();

        // 获取 Spring 定义的 Bean
        String springBean = manager.getBean("springManagedBean");
        Assert.assertEquals("springValue", springBean);

        // 按类型获取
        BeanSample sample = manager.getBean(BeanSample.class);
        Assert.assertNotNull(sample);
        Assert.assertEquals("springSample", sample.getName());

        context.close();
    }

    @Configuration
    static class TestConfig {

        @Bean
        public SpringBeanContainer springBeanContainer() {
            return new SpringBeanContainer();
        }

        @Bean
        public String springManagedBean() {
            return "springValue";
        }

        @Bean
        public BeanSample springSample() {
            BeanSample sample = new BeanSample();
            sample.setName("springSample");
            return sample;
        }
    }
}
