package com.team4u.framework.config.core.spring;

import com.team4u.framework.config.core.ConfigManager;
import com.team4u.framework.config.core.convert.PropertyConverterRegistry;
import com.team4u.framework.config.core.internal.DefaultConfigManager;
import com.team4u.framework.config.core.spi.ConfigSourceRegistry;
import com.team4u.framework.config.core.spi.ConfigWatcherRegistry;
import com.team4u.framework.policy.spring.PolicyAutoRegister;
import com.team4u.framework.policy.spring.SpringPolicyAutoRegistrar;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextRefreshedEvent;

/**
 * Explicitly imported Spring configuration for the shared config registries.
 */
@Configuration
public class Team4uConfigConfiguration {

    @Bean
    @PolicyAutoRegister
    public ConfigSourceRegistry globalSourceRegistry() {
        return ConfigSourceRegistry.global();
    }

    @Bean
    @PolicyAutoRegister
    public ConfigWatcherRegistry globalWatcherRegistry() {
        return ConfigWatcherRegistry.global();
    }

    @Bean
    @PolicyAutoRegister
    public PropertyConverterRegistry globalConverterRegistry() {
        return PropertyConverterRegistry.global();
    }

    @Bean
    public ConfigManager globalConfigManager() {
        return ConfigManager.global();
    }

    @Bean
    public SpringPolicyAutoRegistrar springPolicyAutoRegistrar() {
        return new SpringPolicyAutoRegistrar();
    }

    @Bean
    public ApplicationListener<ContextRefreshedEvent> configRefresher() {
        return event -> DefaultConfigManager.global().refresh();
    }
}
