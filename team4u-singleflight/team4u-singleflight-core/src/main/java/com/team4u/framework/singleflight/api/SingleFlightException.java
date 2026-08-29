package com.team4u.framework.singleflight.api;

/**
 * 回源合并组件异常基类。
 * <p>
 * 加载函数自身抛出的业务异常始终原样上抛，不进入本层次；
 * 只有组件自身的裁决（锁冲突、等待超时、重构的远端失败、配置错误）才使用本异常层次。
 * </p>
 *
 * @author jay.wu
 */
public class SingleFlightException extends RuntimeException {

    public SingleFlightException(String message) {
        super(message);
    }

    public SingleFlightException(String message, Throwable cause) {
        super(message, cause);
    }
}
