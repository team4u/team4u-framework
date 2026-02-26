package com.team4u.framework.base.instance;

import java.util.function.Function;

/**
 * 实例工厂
 * <p>
 * 负责根据配置创建实例
 *
 * @param <C> 配置类型
 * @param <T> 实例类型
 * @author jay.wu
 */
@FunctionalInterface
public interface InstanceFactory<C, T> {

    /**
     * 便捷适配器
     */
    static <C, T> InstanceFactory<C, T> of(Function<C, T> function) {
        return function::apply;
    }

    /**
     * 创建实例
     *
     * @param config 配置对象 (可能为 null，取决于 Parser 的实现)
     * @return 实例
     */
    T create(C config);
}
