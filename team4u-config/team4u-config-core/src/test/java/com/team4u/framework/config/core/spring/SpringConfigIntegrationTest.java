package com.team4u.framework.config.core.spring;

import com.team4u.framework.config.core.ConfigManager;
import com.team4u.framework.config.core.domain.ConfigEntry;
import com.team4u.framework.config.core.spi.ConfigSource;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;

/**
 * Spring 配置集成测试
 *
 * @author jay.wu
 */
public class SpringConfigIntegrationTest {

    @Test
    public void testSpringBeanAutoRegistration() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(
                Team4uConfigAutoConfiguration.class,
                TestConfig.class);

        try {
            ConfigManager manager = context.getBean(ConfigManager.class);
            Assert.assertNotNull("ConfigManager Bean 应该已注册", manager);

            Optional<String> value = manager.getString("test.key");

            Assert.assertTrue("配置项 'test.key' 应该存在", value.isPresent());
            Assert.assertEquals("test.value", value.get());
        } finally {
            context.close();
        }
    }

    @Configuration
    static class TestConfig {

        @Bean
        public ConfigSource testConfigSource() {
            return new ConfigSource() {
                @Override
                public Map<String, ConfigEntry> load() {
                    return Collections.singletonMap("test.key", new ConfigEntry("test.key", "test.value", "test", 0L));
                }

                @Override
                public String name() {
                    return "test";
                }
            };
        }
    }
}
