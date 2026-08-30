package com.team4u.framework.base.config;

import java.util.function.Function;

/**
 * 通用配置解析器
 * <p>
 * 负责将任意类型的输入源转换为具体的配置对象
 *
 * @param <I> 输入源类型 (Input) - 例如 String, InputStream, File, Map 等
 * @param <C> 输出配置类型 (Config)
 */
@FunctionalInterface
public interface ConfigParser<I, C> {

    /**
     * 便捷适配器
     */
    static <I, C> ConfigParser<I, C> of(Function<I, C> function) {
        return function::apply;
    }

    /**
     * 解析配置
     *
     * @param input 输入源 (可能为 null，具体取决于调用方)
     * @return 解析后的配置对象
     */
    C parse(I input);
}
