package com.team4u.framework.singleflight.api;

/**
 * 锁冲突异常：非 WAIT 调用者（FAIL_FAST 策略）在锁竞争中落败时抛出。
 * <p>
 * 不采集堆栈：冲突是高并发的正常结果，构造必须保持廉价，调用方按业务语义处置
 * （重试、报错或忽略）。
 * </p>
 *
 * @author jay.wu
 */
public class SingleFlightConflictException extends SingleFlightException {

    public SingleFlightConflictException(String message) {
        super(message);
    }

    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }
}
