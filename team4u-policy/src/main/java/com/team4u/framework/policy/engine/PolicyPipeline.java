package com.team4u.framework.policy.engine;

import com.team4u.framework.policy.api.ContextPolicy;
import com.team4u.framework.policy.core.OrderedPolicyChain;

import java.util.List;

/**
 * 策略执行流水线 (支持风控检查拦截、责任链执行等场景)
 * 封装引擎提供的规则池，为列表流式顺序处理提供方便的包装。
 *
 * @param <C> 流水线共享计算的环境与上下文的类型信息
 */
public class PolicyPipeline<C, P extends ContextPolicy<C>> {

    private final OrderedPolicyChain<C, P> engine;

    /**
     * 以已完成设定和配置注册的核心路由引擎为底建立新的执行流水线。
     *
     * @param engine 用于进行环境适配和筛选分配的高级路由器
     */
    public PolicyPipeline(OrderedPolicyChain<C, P> engine) {
        this.engine = engine;
    }

    /**
     * 以共享上下文类型的基础结构版本，自动适配任意继承体系内的策略。
     */
    @SuppressWarnings("unchecked")
    public static <C> PolicyPipeline<C, ContextPolicy<C>> of(OrderedPolicyChain<C, ? extends ContextPolicy<C>> engine) {
        return new PolicyPipeline<>((OrderedPolicyChain<C, ContextPolicy<C>>) engine);
    }

    /**
     * 执行所有有效支持并且匹配的策略。
     * 顺序触发过程中按链条逐步向后交付，通过判断外部回传返回值来决定是否终止此循环。
     *
     * @param context 提供用于识别和消费评估处理所需的对象数据
     * @param action  对应针对每一个可触发命中到的对象所开展的操作过程定义闭包
     * @return 如果流水线执行完成且没有被 action 告知打断，则返回 true；否则返回 false。
     */
    public boolean executeChain(C context, PolicyAction<C, P> action) {
        List<P> matchedPolicies = engine.allMatches(context);
        for (P policy : matchedPolicies) {
            boolean shouldContinue = action.execute(policy, context);
            if (!shouldContinue) {
                return false; // 流水线通过外部闭环指令告知响应式中断
            }
        }
        return true;
    }

    /**
     * 流水线内部单步事件执行回调。
     *
     * @param <C> 上下文信息约束泛型同源。
     * @param <P> 策略类型信息约束。
     */
    @FunctionalInterface
    public interface PolicyAction<C, P> {
        /**
         * 启动针对这个被允许触达且命中过滤的单节点运行环节的具体流程动作。
         *
         * @param policy  已验证满足路由条件将要运行参与动作响应的节点载体对象本身
         * @param context 全局持有的环境计算所需的资源聚合中心包载体对象
         * @return 是否继续执行后续流水线流程，返回 false 时意图在告知跳脱打断整体的列表循环。
         */
        boolean execute(P policy, C context);
    }
}
