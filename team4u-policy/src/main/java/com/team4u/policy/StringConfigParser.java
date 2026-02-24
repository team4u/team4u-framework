package com.team4u.policy;

import java.util.function.Function;

/**
 * 基于字符串的配置解析器
 * <p>
 * 专门用于处理 JSON, XML, YAML 等文本格式的配置
 *
 * @param <C> 配置类型
 * @author team4u
 */
@FunctionalInterface
public interface StringConfigParser<C> extends ConfigParser<String, C> {

    /**
     * 便捷适配器
     */
    static <C> StringConfigParser<C> of(Function<String, C> function) {
        return function::apply;
    }
}
