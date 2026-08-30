package com.team4u.framework.flow;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * 分支用例构造器：支持声明多个分支、兜底流程或兜底业务停止，并通过 {@link #end()} 结束分支定义返回主 Builder。
 *
 * @param <I> 流程输入类型
 * @param <C> 分支输入类型（当前值类型）
 * @param <K> 分支选择 key 类型
 * @param <O> 分支输出类型
 * @author jay.wu
 */
public final class ChooseCases<I, C, K, O> {

    private final FlowBuilder<I, C> builder;
    private final String chooseId;
    private final Function<C, K> selector;
    private final Map<K, Flow<C, O>> branches = new LinkedHashMap<>();
    private Flow<C, O> otherwiseBranch;
    private Function<C, StopReason> otherwiseStopReason;

    ChooseCases(FlowBuilder<I, C> builder, String chooseId, Function<C, K> selector) {
        this.builder = Objects.requireNonNull(builder, "builder must not be null");
        this.chooseId = Objects.requireNonNull(chooseId, "chooseId must not be null");
        this.selector = Objects.requireNonNull(selector, "selector must not be null");
    }

    /**
     * 声明一个分支匹配规则。
     *
     * @param key        分支匹配 key，非 null
     * @param branchFlow 分支子流程，非 null
     * @return 当前分支用例构造器
     */
    public ChooseCases<I, C, K, O> when(K key, Flow<C, O> branchFlow) {
        if (key == null) {
            throw new IllegalArgumentException("Choose branch key must not be null");
        }
        if (branchFlow == null) {
            throw new IllegalArgumentException("Choose branchFlow must not be null");
        }
        if (branches.containsKey(key)) {
            throw new IllegalArgumentException("Duplicate branch key [" + key + "] for choose node [" + chooseId + "]");
        }
        branches.put(key, branchFlow);
        return this;
    }

    /**
     * 声明未匹配到任何显式 key 时的兜底分支。
     *
     * @param branchFlow 兜底子流程，非 null
     * @return 当前分支用例构造器
     */
    public ChooseCases<I, C, K, O> otherwise(Flow<C, O> branchFlow) {
        if (branchFlow == null) {
            throw new IllegalArgumentException("Otherwise branchFlow must not be null");
        }
        if (this.otherwiseBranch != null || this.otherwiseStopReason != null) {
            throw new IllegalStateException("Otherwise already declared for choose node [" + chooseId + "]");
        }
        this.otherwiseBranch = branchFlow;
        return this;
    }

    /**
     * 声明未匹配到任何显式 key 时的业务停止规则。
     *
     * @param reasonFactory 停止原因工厂函数，非 null
     * @return 当前分支用例构造器
     */
    public ChooseCases<I, C, K, O> otherwiseStop(Function<C, StopReason> reasonFactory) {
        if (reasonFactory == null) {
            throw new IllegalArgumentException("otherwiseStop reasonFactory must not be null");
        }
        if (this.otherwiseBranch != null || this.otherwiseStopReason != null) {
            throw new IllegalStateException("Otherwise already declared for choose node [" + chooseId + "]");
        }
        this.otherwiseStopReason = reasonFactory;
        return this;
    }

    /**
     * 结束 choose 分支定义，将分支节点挂载至父 FlowBuilder 并返回输出类型为 O 的新 FlowBuilder。
     *
     * @return 新 FlowBuilder
     */
    public FlowBuilder<I, O> end() {
        return builder.addChoose(chooseId, selector, branches, otherwiseBranch, otherwiseStopReason);
    }
}
