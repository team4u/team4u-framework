package com.team4u.framework.flow.api;

import com.team4u.framework.flow.model.Outcome;
import com.team4u.framework.flow.model.ParallelResults;

/**
 * 携带父节点输入上下文的并行汇聚归约策略 SPI。
 *
 * <p>在结构化并行（Parallel）节点中，当汇聚归约需要同时基于进入并行节点时的原始输入（Parent Input）
 * 与各分支执行产出的结果集合（Branch Outcomes）进行联合决策或按序状态折叠（如 {@code parallelFill}）时，
 * 实现此接口扩展默认的 {@link JoinStrategy}。</p>
 *
 * @param <I> 并行父节点的原始输入数据类型
 * @param <O> 汇聚计算后的最终输出数据类型
 * @author jay.wu
 */
@FunctionalInterface
public interface ContextualJoinStrategy<I, O> extends JoinStrategy<O> {

    /**
     * 结合父节点原始输入与分支结果集合进行汇聚归约。
     *
     * @param input   并行节点的原始输入对象
     * @param results 并行分支的保序结果集合
     * @return 汇聚计算后的最终四态结果
     * @throws Exception 当汇聚计算逻辑发生异常时抛出
     */
    Outcome<O> join(I input, ParallelResults results);

    @Override
    default Outcome<O> join(ParallelResults results) {
        throw new UnsupportedOperationException("ContextualJoinStrategy requires parent input context");
    }
}
