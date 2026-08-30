package com.team4u.framework.flow;

/**
 * 无返回值副作用动作合同：接收当前值并执行副作用，流程将原样传递该值。
 *
 * @param <I> 输入类型
 * @author jay.wu
 */
@FunctionalInterface
public interface Action<I> {

    /**
     * 执行副作用动作。
     *
     * @param input 当前值，非 null
     * @throws Exception 业务执行异常
     */
    void execute(I input) throws Exception;

    /**
     * 上下文型副作用动作合同：额外接收执行上下文。
     *
     * @param <I> 输入类型
     */
    @FunctionalInterface
    interface Contextual<I> {

        /**
         * 执行副作用动作。
         *
         * @param context 节点执行上下文，非 null
         * @param input   当前值，非 null
         * @throws Exception 业务执行异常
         */
        void execute(StepContext context, I input) throws Exception;
    }
}
