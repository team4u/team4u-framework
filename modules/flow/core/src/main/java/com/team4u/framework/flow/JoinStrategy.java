package com.team4u.framework.flow;

/**
 * 并行（Parallel）分支全部执行完成（Wait-All）后的类型化汇聚归约策略 SPI。
 *
 * <p>在并行节点中，所有分支以结构化并发方式并行执行并等待全部完成后，
 * 引擎将各分支的执行结果集合以保序的 {@link ParallelResults} 容器传入本策略，
 * 最终计算并归约为单个 {@link Outcome} 输出。</p>
 *
 * <p>框架内置了常见汇聚策略（参见 {@link ParallelResults}）：
 * <ul>
 *   <li>{@link ParallelResults#allAccepted(java.util.function.Function)}：全部分支必须均 Accepted，将所有值聚合后输出；</li>
 *   <li>{@link ParallelResults#firstAccepted(Reason)}：取首个 Accepted 的分支输出，全失败/拒绝时返回指定 Reason；</li>
 *   <li>{@link ParallelResults#quorum(int, Reason, java.util.function.Function)}：法定多数/配额汇聚；</li>
 *   <li>{@link ParallelResults#homogeneousCollect()}：同构分支结果集合收集。</li>
 * </ul>
 * </p>
 *
 * @param <O> 汇聚计算后的最终输出结果类型
 * @author jay.wu
 */
@FunctionalInterface
public interface JoinStrategy<O> {

    /**
     * 对并行分支的结果集合进行聚合裁决。
     *
     * @param results 保留分支声明顺序的并行结果视图容器，包含每个分支的 {@link Branch} 令牌与对应的 {@link Outcome}
     * @return 汇聚计算后的最终四态结果（Accepted/Rejected/Skipped/Failed）
     * @throws Exception 当汇聚计算逻辑发生异常时抛出（将被框架捕获并转换为 Failed 结果）
     */
    Outcome<O> join(ParallelResults results);
}

