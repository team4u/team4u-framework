package com.team4u.framework.config.core.domain;

/**
 * 配置项缺失异常
 * <p>
 * 当访问标记为必填的配置项，但在配置源中未找到对应值且没有指定默认值时，将抛出此异常。
 * </p>
 *
 * @author jay.wu
 */
public class ConfigMissingException extends RuntimeException {

    public ConfigMissingException(String message) {
        super(message);
    }
}
