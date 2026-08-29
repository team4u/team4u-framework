package com.team4u.framework.singleflight.api;

/**
 * 重构的执行失败：交付给复用失败会话的 WAIT 调用者。
 * <p>
 * 只有本地执行加载函数的调用者收到原始业务异常；其他线程或实例只能从失败会话
 * 读取错误信息并收到本异常——组件不承诺跨线程、跨实例重建原异常对象。
 * message 来自原异常（原异常 message 为空时退化为异常类名）。
 * </p>
 *
 * @author jay.wu
 */
public class SingleFlightExecutionException extends SingleFlightException {

    public SingleFlightExecutionException(String message) {
        super(message);
    }

    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }
}
