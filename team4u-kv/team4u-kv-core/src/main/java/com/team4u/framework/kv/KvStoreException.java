package com.team4u.framework.kv;

/**
 * 键值存储异常
 * <p>
 * 所有 {@link KvStore} 实现的基础设施故障统一以本异常（或其子类）抛出，
 * 用于区分「键不存在」（返回 {@code null}/{@code false}）与「存储不可用」（抛异常）。
 * 对齐框架惯例（如 {@code ProxyException}、{@code TaskInfrastructureException}）。
 * </p>
 *
 * @author jay.wu
 */
public class KvStoreException extends RuntimeException {

    public KvStoreException(String message) {
        super(message);
    }

    public KvStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
