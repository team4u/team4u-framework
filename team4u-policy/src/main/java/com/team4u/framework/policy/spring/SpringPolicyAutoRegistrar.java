package com.team4u.framework.policy.spring;

import cn.hutool.log.Log;
import com.team4u.framework.policy.api.PolicyRegistry;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import java.util.Collection;
import java.util.Map;

/**
 * Spring 策略自动注册器
 * <p>
 * 监听 Spring 容器初始化完成事件，自动发现容器内的 PolicyRegistry，
 * 并将对应的 Policy 自动注入到对应的 Registry 中。
 *
 * @author jay.wu
 */
public class SpringPolicyAutoRegistrar implements SmartInitializingSingleton, ApplicationContextAware {

    private static final Log log = Log.get();
    private ApplicationContext context;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        this.context = applicationContext;
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void afterSingletonsInstantiated() {
        // 1. 获取 Spring 容器中所有的策略注册表 (如 KeyedPolicyRegistry, OrderedPolicyChain)
        Map<String, PolicyRegistry> registries = context.getBeansOfType(PolicyRegistry.class);

        if (registries.isEmpty()) {
            return;
        }

        // 2. 遍历每个注册表，根据其持有的 policyClass 去 Spring 容器中找对应的策略 Bean
        for (Map.Entry<String, PolicyRegistry> entry : registries.entrySet()) {
            String registryBeanName = entry.getKey();
            PolicyRegistry registry = entry.getValue();

            // 检查该 Bean 是否有 @PolicyAutoRegister 注解
            PolicyAutoRegister annotation = context.findAnnotationOnBean(registryBeanName, PolicyAutoRegister.class);
            if (annotation == null) {
                log.debug("SpringPolicyAutoRegistrar|autoRegister|skip|registry={}|reason=noAnnotation", registryBeanName);
                continue;
            }

            Class<?> policyClass = registry.getPolicyClass();
            if (policyClass == null) {
                log.warn("SpringPolicyAutoRegistrar|autoRegister|skip|registry={}|reason=policyClassIsNull", registryBeanName);
                continue;
            }

            // 3. 找出所有该策略类型的 Spring Bean
            Map<String, ?> policyBeans = context.getBeansOfType(policyClass);

            // 4. 将这些 Bean 批量注册到注册表中
            if (!policyBeans.isEmpty()) {
                registry.addAll(policyBeans.values());
                log.info("SpringPolicyAutoRegistrar|autoRegister|success|registry={}|policyClass={}|count={}",
                        registryBeanName, policyClass.getSimpleName(), policyBeans.size());
            }
        }
    }
}
