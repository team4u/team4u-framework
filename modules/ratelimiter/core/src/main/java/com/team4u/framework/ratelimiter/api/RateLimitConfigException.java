package com.team4u.framework.ratelimiter.api;

/**
 * 限流规则解析/校验异常
 * <p>
 * 规则 JSON 反序列化失败或字段校验不通过时抛出；
 * 配置热更新场景下由注册表捕获并保留旧规则（热更失败保旧）。
 * </p>
 *
 * @author jay.wu
 */
public class RateLimitConfigException extends RuntimeException {

    public RateLimitConfigException(String message) {
        super(message);
    }

    public RateLimitConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
