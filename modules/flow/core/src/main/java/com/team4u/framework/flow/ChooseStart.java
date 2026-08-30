package com.team4u.framework.flow;

import java.util.Objects;
import java.util.function.Function;

/**
 * 分支构造器起始阶段：用于在第一个 {@link #when} 时绑定分支输出类型。
 *
 * @param <I> 流程输入类型
 * @param <C> 分支输入类型（当前值类型）
 * @param <K> 分支选择 key 类型
 * @author jay.wu
 */
public final class ChooseStart<I, C, K> {

    private final FlowBuilder<I, C> builder;
    private final String chooseId;
    private final Function<C, K> selector;

    ChooseStart(FlowBuilder<I, C> builder, String chooseId, Function<C, K> selector) {
        this.builder = Objects.requireNonNull(builder, "builder must not be null");
        this.chooseId = Objects.requireNonNull(chooseId, "chooseId must not be null");
        this.selector = Objects.requireNonNull(selector, "selector must not be null");
    }

    /**
     * 声明第一个分支匹配规则并绑定输出类型。
     *
     * @param key        分支匹配 key，非 null
     * @param branchFlow 分支子流程，非 null
     * @param <O>        分支输出类型
     * @return 分支用例构造器
     */
    public <O> ChooseCases<I, C, K, O> when(K key, Flow<C, O> branchFlow) {
        ChooseCases<I, C, K, O> cases = new ChooseCases<>(builder, chooseId, selector);
        return cases.when(key, branchFlow);
    }
}
