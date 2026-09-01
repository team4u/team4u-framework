package com.team4u.framework.flow.definition.registry;

import com.team4u.framework.flow.api.JoinStrategy;
import com.team4u.framework.flow.spi.OperationResolver;

/**
 * 并行汇聚策略动态提供者（Join Provider），支持 IoC 依赖注入与动态构建。
 *
 * @author jay.wu
 */
public interface JoinProvider {

    /**
     * 获取汇聚策略描述符。
     *
     * @return 汇聚描述符
     */
    JoinDescriptor descriptor();

    /**
     * 使用依赖解析器提供汇聚策略实例。
     *
     * @param resolver 组件解析器（如 Spring Bean 解析器）
     * @return 汇聚策略实例
     */
    JoinStrategy<?> provide(OperationResolver resolver);
}
