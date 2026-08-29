package com.team4u.framework.singleflight.api;

/**
 * 配置类异常：规则无法加载，或规则行为对当前执行请求不可用
 * （例如基本类型返回值配了显式 null 降级、规则 id 与 point 不一致、存储未注册等）。
 *
 * @author jay.wu
 */
public class SingleFlightConfigException extends SingleFlightException {

    public SingleFlightConfigException(String message) {
        super(message);
    }

    public SingleFlightConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
