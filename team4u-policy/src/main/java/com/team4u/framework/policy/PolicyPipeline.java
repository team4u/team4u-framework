package com.team4u.framework.policy;

import java.util.List;

/**
 * 策略执行流水线 (支持风控检查拦截、责任链执行等场景)
 * 封装引擎提供的规则池，为列表流式顺序处理提供方便的包装。
 *
 * @param <C> 流水线共享计算的环境与上下文的类型信息
 */
public class PolicyPipeline<C> {

    private final OrderedPolicyChain<C, ContextPolicy<C>> engine;

    /**
     * 以已完成设定和配置注册的核心路由引擎为底建立新的执行流水线。
     *
     * @param engine 用于进行环境适配和筛选分配的高级路由器
     */
    public PolicyPipeline(OrderedPolicyChain<C, ContextPolicy<C>> engine) {
        this.engine = engine;
    }

    /**
     * 执行所有有效支持并且匹配的策略。
     * 顺序触发过程中按链条逐步向后交付，通过判断外部回传返回值来决定是否终止此循环。
     *
     * @param context 提供用于识别和消费评估处理所需的对象数据
     * @param action  对应针对每一个可触发命中到的对象所开展的操作过程定义闭包
     */
    public void executeChain(C context, PolicyAction<C> action) {
        List<ContextPolicy<C>> matchedPolicies = engine.allMatches(context);
        for (ContextPolicy<C> policy : matchedPolicies) {
            boolean shouldContinue = action.execute(policy, context);
            if (!shouldContinue) {
                break; // 流水线正常响应式中断
            }
        }
    }

    /**
     * 流水线内部单步事件执行回调。
     *
     * @param <C> 上下文信息约束泛型同源。
     */
    @FunctionalInterface
    public interface PolicyAction<C> {
        /**
         * 启动针对这个被允许触达且命中过滤的单节点运行环节的具体流程动作。
         *
         * @param policy  已验证满足路由条件将要运行参与动作响应的节点载体对象本身
         * @param context 全局持有的环境计算所需的资源聚合中心包载体对象
         * @return 是否继续执行后续流水线流程，返回 false 时意图在告知跳脱打断整体的列表循环。
         */
        boolean execute(ContextPolicy<C> policy, C context);
    }
}
