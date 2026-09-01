package com.team4u.framework.flow.definition.registry;

/**
 * 流程定义注册表扩展 SPI 接口（Flow Definition Extension）。
 *
 * <p>允许各个子模块（如 flow-retry、flow-ratelimiter 等）向 Registry 注册其特有的策略 Provider 与描述符，
 * 使 DSL 主模块无需直接强依赖扩展模块。</p>
 *
 * @author jay.wu
 */
public interface FlowDefinitionExtension {

    /**
     * 向注册表构建器贡献描述符、策略提供者或编解码器。
     *
     * @param registry 注册表构建器
     */
    void contribute(FlowDefinitionRegistry.Builder registry);
}
