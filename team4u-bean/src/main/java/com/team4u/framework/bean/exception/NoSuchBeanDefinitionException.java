package com.team4u.framework.bean.exception;

/**
 * 未找到 Bean 定义异常
 *
 * @author jay.wu
 */
public class NoSuchBeanDefinitionException extends RuntimeException {

    public NoSuchBeanDefinitionException(String message) {
        super(message);
    }
}
