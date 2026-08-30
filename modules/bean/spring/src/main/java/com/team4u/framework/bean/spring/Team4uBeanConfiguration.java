package com.team4u.framework.bean.spring;

import com.team4u.framework.bean.provider.SpringBeanContainer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class Team4uBeanConfiguration {

    @Bean
    public SpringBeanContainer springBeanContainer() {
        return new SpringBeanContainer();
    }
}
