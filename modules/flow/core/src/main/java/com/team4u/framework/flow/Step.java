package com.team4u.framework.flow;

/**
 * 业务步骤合同：接收输入值并返回转换后的输出值。
 *
 * @param <I> 输入类型
 * @param <O> 输出类型
 * @author jay.wu
 */
@FunctionalInterface
public interface Step<I, O> {

    /**
     * 执行业务步骤。
     *
     * @param input 输入值，非 null
     * @return 转换后的输出值，非 null
     * @throws Exception 业务执行异常
     */
    O apply(I input) throws Exception;

    /**
     * 上下文型业务步骤合同：额外接收执行上下文。
     *
     * @param <I> 输入类型
     * @param <O> 输出类型
     */
    @FunctionalInterface
    interface Contextual<I, O> {

        /**
         * 执行业务步骤。
         *
         * @param context 节点执行上下文，非 null
         * @param input   输入值，非 null
         * @return 转换后的输出值，非 null
         * @throws Exception 业务执行异常
         */
        O apply(StepContext context, I input) throws Exception;
    }
}
