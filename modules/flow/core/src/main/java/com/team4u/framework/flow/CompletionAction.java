package com.team4u.framework.flow;

/**
 * 清理动作合同：在作用域完成（无论成功、停止或失败）时执行清理。
 *
 * @param <I> 作用域入口输入类型
 * @param <O> 作用域输出类型
 * @author jay.wu
 */
@FunctionalInterface
public interface CompletionAction<I, O> {

    /**
     * 执行清理动作。
     *
     * @param input      作用域入口输入值，非 null
     * @param completion 作用域完成状态，非 null
     * @throws Exception 清理异常
     */
    void onComplete(I input, CompletionContext<O> completion) throws Exception;

    /**
     * 上下文型清理动作合同：额外接收代表 ensure 节点自身的 StepContext。
     *
     * @param <I> 作用域入口输入类型
     * @param <O> 作用域输出类型
     */
    @FunctionalInterface
    interface Contextual<I, O> {

        /**
         * 执行清理动作。
         *
         * @param context    代表 ensure 节点自身的上下文，非 null
         * @param input      作用域入口输入值，非 null
         * @param completion 作用域完成状态，非 null
         * @throws Exception 清理异常
         */
        void onComplete(StepContext context, I input, CompletionContext<O> completion) throws Exception;
    }
}
