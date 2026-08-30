package com.team4u.framework.flow;

/**
 * 同步、可复用、线程安全的业务步骤扩展点。实现应避免持有跨调用可变状态。
 */
@FunctionalInterface
public interface Operation<I, O> {
    Outcome<O> execute(OperationContext context, I input) throws Exception;
}
