package com.team4u.framework.criterion.spring;

import com.team4u.framework.criterion.Criteria;
import com.team4u.framework.criterion.compiler.CompilerRegistry;
import com.team4u.framework.criterion.model.convert.ValueConverterRegistry;
import com.team4u.framework.criterion.parser.impl.StandardCriterionParser;
import com.team4u.framework.policy.spring.PolicyAutoRegister;
import com.team4u.framework.policy.spring.SpringPolicyAutoRegistrar;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 表达式引擎自动配置
 * <p>
 * 将全局共享注册表暴露为 Spring Bean，并开启自动装配逻辑。
 *
 * @author gemini-cli
 */
@Configuration
public class Team4uCriterionAutoConfiguration {

    /**
     * 将全局表达式引擎单例暴露为 Bean
     */
    @Bean
    public Criteria globalCriteria() {
        return Criteria.global();
    }

    /**
     * 将全局解析器暴露为 Bean
     */
    @Bean
    public StandardCriterionParser globalCriterionParser() {
        return StandardCriterionParser.global();
    }

    /**
     * 将全局编译器注册表暴露为 Bean，并开启自动填充
     */
    @Bean
    @PolicyAutoRegister
    public CompilerRegistry globalCompilerRegistry() {
        return CompilerRegistry.global();
    }

    /**
     * 将全局转换器注册表暴露为 Bean，并开启自动填充
     */
    @Bean
    @PolicyAutoRegister
    public ValueConverterRegistry globalValueConverterRegistry() {
        return ValueConverterRegistry.global();
    }

    /**
     * 注册自动注册器基础设施（如果尚未注册）
     */
    @Bean
    public SpringPolicyAutoRegistrar springPolicyAutoRegistrar() {
        return new SpringPolicyAutoRegistrar();
    }
}
