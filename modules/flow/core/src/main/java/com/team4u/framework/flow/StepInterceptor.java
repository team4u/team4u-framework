package com.team4u.framework.flow;

/**
 * 步骤拦截器：around/proceed 模式包装业务 Step 和 Tap 执行。
 *
 * @author jay.wu
 */
@FunctionalInterface
public interface StepInterceptor {

    /**
     * 拦截步骤执行。
     *
     * @param chain 拦截调用链
     * @param <I>   输入类型
     * @param <O>   输出类型
     * @return 步骤输出（非 null）
     * @throws Exception 执行异常
     */
    <I, O> O intercept(Chain<I, O> chain) throws Exception;

    /**
     * 拦截调用链接口。
     *
     * @param <I> 输入类型
     * @param <O> 输出类型
     */
    interface Chain<I, O> {

        /**
         * 节点上下文。
         */
        StepContext context();

        /**
         * 当前输入值。
         */
        I input();

        /**
         * 推进拦截链或调用目标节点。
         *
         * @param input 输入值，非 null
         * @return 节点输出值，非 null
         * @throws Exception 业务或拦截异常
         */
        O proceed(I input) throws Exception;
    }
}
