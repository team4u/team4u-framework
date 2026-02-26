package com.team4u.framework.proxy.core;

/**
 * 代理组件全局基础异常
 *
 * @author jay.wu
 */
public class ProxyException extends RuntimeException {

    public ProxyException(String message) {
        super(message);
    }

    public ProxyException(String message, Throwable cause) {
        super(message, cause);
    }

    public ProxyException(Throwable cause) {
        super(cause);
    }
}
