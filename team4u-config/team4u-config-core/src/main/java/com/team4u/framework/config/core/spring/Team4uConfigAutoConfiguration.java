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
 * 共享注册表模式自动配置
 * <p>
 * 将全局共享注册表暴露为 Spring Bean，并开启自动装配逻辑。
 * </p>
 *
 * @author gemini-cli
 */
@Configuration
public class Team4uConfigAutoConfiguration {

    /**
     * 将全局配置源注册表暴露为 Bean，并开启自动填充
     */
    @Bean
    @PolicyAutoRegister
    public ConfigSourceRegistry globalSourceRegistry() {
        return ConfigSourceRegistry.global();
    }

    /**
     * 将全局监控注册表暴露为 Bean，并开启自动填充
     */
    @Bean
    @PolicyAutoRegister
    public ConfigWatcherRegistry globalWatcherRegistry() {
        return ConfigWatcherRegistry.global();
    }

    /**
     * 将全局转换器注册表暴露为 Bean，并开启自动填充
     */
    @Bean
    @PolicyAutoRegister
    public PropertyConverterRegistry globalConverterRegistry() {
        return PropertyConverterRegistry.global();
    }

    /**
     * 将全局配置管理器暴露为 Bean
     */
    @Bean
    public ConfigManager globalConfigManager() {
        return ConfigManager.global();
    }

    /**
     * 注册自动注册器基础设施（如果尚未注册）
     */
    @Bean
    public SpringPolicyAutoRegistrar springPolicyAutoRegistrar() {
        return new SpringPolicyAutoRegistrar();
    }

    /**
     * 监听容器刷新事件，当所有 Bean 初始化并注入注册表后，通知 ConfigManager 刷新快照
     */
    @Bean
    public ApplicationListener<ContextRefreshedEvent> configRefresher() {
        return event -> DefaultConfigManager.global().refresh();
    }
}
