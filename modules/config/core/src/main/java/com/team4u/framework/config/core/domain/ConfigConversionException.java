package com.team4u.framework.config.core.domain;

/**
 * 配置值转换失败异常。
 */
public class ConfigConversionException extends RuntimeException {

    public ConfigConversionException(String message, Throwable cause) {
        super(message, cause);
    }
}
