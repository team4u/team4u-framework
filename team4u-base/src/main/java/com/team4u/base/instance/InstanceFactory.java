package com.team4u.base.instance;

import java.util.function.BiFunction;

/**
 * 实例工厂
 * <p>
 * 负责根据配置创建实例
 *
 * @param <C> 配置类型
 * @param <T> 实例类型
 * @author team4u
 */
@FunctionalInterface
public interface InstanceFactory<C, T> {

    /**
     * 便捷适配器
     */
    static <C, T> InstanceFactory<C, T> of(BiFunction<String, C, T> function) {
        return function::apply;
    }

    /**
     * 创建实例
     *
     * @param configId 配置唯一标识
     * @param config   配置对象 (可能为 null，取决于 Parser 的实现)
     * @return 实例
     */
    T create(String configId, C config);
}
