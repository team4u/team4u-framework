package com.team4u.framework.flow;

/**
 * 失败恢复回调合同：在作用域发生 FAILED 时调用，决定转为成功、停止或新的失败。
 *
 * @param <I> 作用域入口输入类型
 * @param <O> 作用域期望输出类型
 * @author jay.wu
 */
@FunctionalInterface
public interface Recovery<I, O> {

    /**
     * 执行恢复逻辑。
     *
     * @param input   作用域入口输入值，非 null
     * @param failure 原始失败上下文，非 null
     * @return 恢复后的流程结果（可为 SUCCEEDED、STOPPED 或 FAILED）
     * @throws Exception 恢复执行抛出异常时将作为新的失败主因，原始异常将被加入 suppressed
     */
    FlowResult<O> recover(I input, FailureContext failure) throws Exception;

    /**
     * 上下文型失败恢复合同：额外接收代表 recover 节点自身的 StepContext。
     *
     * @param <I> 作用域入口输入类型
     * @param <O> 作用域期望输出类型
     */
    @FunctionalInterface
    interface Contextual<I, O> {

        /**
         * 执行恢复逻辑。
         *
         * @param context 代表 recover 节点自身的上下文，非 null
         * @param input   作用域入口输入值，非 null
         * @param failure 原始失败上下文，非 null
         * @return 恢复后的流程结果
         * @throws Exception 恢复执行异常
         */
        FlowResult<O> recover(StepContext context, I input, FailureContext failure) throws Exception;
    }
}
