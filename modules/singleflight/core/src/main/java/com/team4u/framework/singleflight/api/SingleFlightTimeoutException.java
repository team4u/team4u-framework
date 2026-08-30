package com.team4u.framework.singleflight.api;

/**
 * 等待超时异常：WAIT 调用者在 {@code waitTimeoutMillis} 内既没等到终态会话，
 * 也没等到接管机会（含等待中被中断的场景）。
 * <p>
 * 不采集堆栈：竞争下的超时属于预期结果。
 * </p>
 *
 * @author jay.wu
 */
public class SingleFlightTimeoutException extends SingleFlightException {

    public SingleFlightTimeoutException(String message) {
        super(message);
    }

    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }
}
