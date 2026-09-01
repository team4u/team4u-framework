package com.team4u.framework.flow.definition.registry;

import java.util.Map;

/**
 * 策略动态生成提供者（Policy Provider），根据 DSL 传入的配置参数动态构造策略绑定。
 *
 * @author jay.wu
 */
public interface PolicyProvider {

    /**
     * 获取策略描述符。
     *
     * @return 策略描述符
     */
    PolicyDescriptor descriptor();

    /**
     * 根据 DSL 文本配置参数构造策略绑定实例。
     *
     * @param configuration 键值配置 Map
     * @return 策略绑定模型
     */
    PolicyBinding create(Map<String, Object> configuration);
}
