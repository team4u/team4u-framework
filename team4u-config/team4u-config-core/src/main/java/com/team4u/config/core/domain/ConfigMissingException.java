package com.team4u.config.core.domain;

/**
 * 配置项缺失异常
 * <p>
 * 当必填配置项缺失且无默认值时抛出。
 *
 * @author fjay
 */
public class ConfigMissingException extends RuntimeException {

    public ConfigMissingException(String message) {
        super(message);
    }
}
