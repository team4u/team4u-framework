package com.team4u.framework.flow;

import com.team4u.framework.flow.api.JoinStrategy;
import com.team4u.framework.flow.model.Outcome;
import com.team4u.framework.flow.model.ParallelResults;

import java.util.List;

/**
 * 常见通用并行汇聚归约策略工具与工厂类。
 *
 * <p>为 Java API 与 DSL 内置汇聚指令（{@code join all}、{@code join first}、{@code join collect}、
 * {@code join quorum}）提供统一的归约算子实现。</p>
 *
 * @author jay.wu
 */
public final class Joins {

    private Joins() { }

    /**
     * 全票成功聚合策略：所有分支必须均 Accepted，输出包含各分支值的 {@link ParallelResults.Values}；
     * 存在任一非 Accepted 分支时，按分支声明顺序返回首个非 Accepted 结果。
     *
     * @return 全票成功聚合策略
     */
    public static JoinStrategy<ParallelResults.Values> allAccepted() {
        return ParallelResults::allAccepted;
    }

    /**
     * 首选成功聚合策略：按分支声明顺序检索并返回首个为 Accepted 的分支输出；
     * 若全部分支均未成功，则返回携带 {@code NO_APPLICABLE_BRANCH} 的 Skipped 结果。
     *
     * @param <O> 分支输出类型
     * @return 首选成功聚合策略
     */
    @SuppressWarnings("unchecked")
    public static <O> JoinStrategy<O> firstAccepted() {
        return results -> (Outcome<O>) results.firstAccepted();
    }

    /**
     * 同质列表收集策略：所有分支均 Accepted 时，按声明顺序将其输出载荷收集为只读列表返回；
     * 只要存在任一非 Accepted 分支，则原样返回首个非 Accepted 结果。
     *
     * @param <T> 元素类型
     * @return 列表收集聚合策略
     */
    @SuppressWarnings("unchecked")
    public static <T> JoinStrategy<List<T>> collect() {
        return results -> (Outcome<List<T>>) (Outcome<?>) results.homogeneousCollect();
    }

    /**
     * 法定多数/配额聚合策略：当 Accepted 的分支数量达到或超过 {@code required} 阈值时返回 Values；
     * 否则返回错误码为 {@code QUORUM_NOT_REACHED} 的 Failed 结果。
     *
     * @param required 要求的最小成功分支数
     * @return 法定配额聚合策略
     */
    public static JoinStrategy<ParallelResults.Values> quorum(int required) {
        if (required < 1) {
            throw new IllegalArgumentException("required must be at least 1, got: " + required);
        }
        return results -> results.quorum(required);
    }

    /**
     * 全票成功屏障策略（透传并行原输入）：所有分支必须均 Accepted，输出保留原始输入上下文；
     * 存在任一非 Accepted 分支时，按分支声明顺序返回首个非 Accepted 结果。
     *
     * @param <I> 输入类型
     * @return 全票成功上下文屏障策略
     */
    public static <I> com.team4u.framework.flow.api.ContextualJoinStrategy<I, I> allAcceptedBarrier() {
        return (initialInput, results) -> {
            for (com.team4u.framework.flow.api.Branch<?, ?> branch : results.branches()) {
                Outcome<?> outcome = results.outcome(branch);
                if (!(outcome instanceof Outcome.Accepted)) {
                    @SuppressWarnings("unchecked")
                    Outcome<I> nonAccepted = (Outcome<I>) outcome;
                    return nonAccepted;
                }
            }
            return Outcome.accepted(initialInput);
        };
    }

    /**
     * 法定配额成功屏障策略（透传并行原输入）：当 Accepted 的分支数量达到或超过 {@code required} 阈值时返回原始输入上下文；
     * 否则返回错误码为 {@code QUORUM_NOT_REACHED} 的 Failed 结果。
     *
     * @param required 要求的最小成功分支数
     * @param <I>      输入类型
     * @return 法定配额上下文屏障策略
     */
    public static <I> com.team4u.framework.flow.api.ContextualJoinStrategy<I, I> quorumBarrier(int required) {
        if (required < 1) {
            throw new IllegalArgumentException("required must be at least 1, got: " + required);
        }
        return (initialInput, results) -> {
            if (required > results.branches().size()) {
                throw new IllegalArgumentException("required quorum " + required + " exceeds branch count " + results.branches().size());
            }
            int acceptedCount = 0;
            for (com.team4u.framework.flow.api.Branch<?, ?> branch : results.branches()) {
                if (results.outcome(branch) instanceof Outcome.Accepted) {
                    acceptedCount++;
                }
            }
            if (acceptedCount >= required) {
                return Outcome.accepted(initialInput);
            }
            return Outcome.failed(com.team4u.framework.flow.model.Failure.of("QUORUM_NOT_REACHED",
                    "Required quorum of " + required + " not reached, actual: " + acceptedCount));
        };
    }
}
