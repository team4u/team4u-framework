package com.team4u.policy;

import java.util.function.BiFunction;

/**
 * 策略工厂
 * <p>
 * 负责根据配置创建策略实例
 *
 * @param <C> 配置类型
 * @param <P> 策略类型
 * @author team4u
 */
@FunctionalInterface
public interface PolicyFactory<C, P> {

    /**
     * 便捷适配器
     */
    static <C, P> PolicyFactory<C, P> of(BiFunction<String, C, P> function) {
        return function::apply;
    }

    /**
     * 创建策略
     *
     * @param configId 配置唯一标识
     * @param config   配置对象 (可能为 null，取决于 Parser 的实现)
     * @return 策略实例
     */
    P create(String configId, C config);
}
