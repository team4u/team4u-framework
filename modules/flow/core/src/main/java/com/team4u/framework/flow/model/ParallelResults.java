package com.team4u.framework.flow.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import com.team4u.framework.flow.api.Branch;

/**
 * 结构化并行（Parallel）分支执行完成后的保序结果集合容器。
 *
 * <p>特性与功能：
 * <ul>
 *   <li><b>严格保序与一致性</b>：内部保留并行分支声明时的原始顺序，分支数量与结果数量严格一致且一一对应；</li>
 *   <li><b>类型化令牌检索</b>：通过 {@link #outcome(Branch)} 可以按分支令牌类型安全检索对应分支的具体 {@link Outcome}；</li>
 *   <li><b>内置常见汇聚策略</b>：提供 {@link #allAccepted()}、{@link #firstAccepted()}、{@link #quorum(int)}、{@link #homogeneousCollect()} 等通用聚合算子。</li>
 * </ul>
 * </p>
 *
 * @author jay.wu
 */
public final class ParallelResults {
    @lombok.Getter
    @lombok.experimental.Accessors(fluent = true)
    private final List<Branch<?, ?>> branches;
    private final List<Outcome<?>> outcomes;
    public ParallelResults(List<Branch<?, ?>> branches, List<Outcome<?>> outcomes) {
        this(validate(branches, outcomes));
    }

    private ParallelResults(Validated validated) {
        this.branches = validated.branches;
        this.outcomes = validated.outcomes;
    }

    /**
     * 创建保序的不可变并行分支结果集合。
     *
     * @param branches 并行分支令牌列表，不能为 null
     * @param outcomes 对应分支的四态结果列表，不能为 null
     * @return {@link ParallelResults} 容器实例
     * @throws NullPointerException     当任何入参或元素为 null 时抛出
     * @throws IllegalArgumentException 当分支与结果数量不一致或存在重复分支令牌时抛出
     */
    public static ParallelResults of(List<Branch<?, ?>> branches, List<Outcome<?>> outcomes) {
        return new ParallelResults(validate(branches, outcomes));
    }

    /**
     * 按分支令牌类型安全地检索该分支的执行结果。
     *
     * @param branch 目标分支令牌，不能为 null 且必须属于本并行块
     * @param <T>    分支输出类型
     * @return 该分支的四态结果 {@link Outcome}
     * @throws NullPointerException     当 {@code branch} 为 null 时抛出
     * @throws IllegalArgumentException 当 {@code branch} 不属于当前并行结果集合时抛出
     */
    public <T> Outcome<T> outcome(Branch<?, T> branch) {
        return cast(outcomes.get(indexOf(branch)));
    }

    /**
     * 全票成功聚合（All-Accepted）：
     * 当且仅当所有并行分支均返回 {@link Outcome.Accepted} 时返回包含各分支输出值的 {@link Values}；
     * 只要有任一分支非 Accepted，则短路返回首个非 Accepted 的结果（Rejected/Skipped/Failed）。
     *
     * @return 聚合后的 {@link Values} 结果
     */
    public Outcome<Values> allAccepted() {
        IdentityHashMap<Branch<?, ?>, Object> values = new IdentityHashMap<Branch<?, ?>, Object>();
        for (int index = 0; index < branches.size(); index++) {
            Outcome<?> outcome = outcomes.get(index);
            if (!(outcome instanceof Outcome.Accepted)) {
                return cast(outcome);
            }
            values.put(branches.get(index), ((Outcome.Accepted<?>) outcome).value());
        }
        return Outcome.accepted(new Values(values));
    }

    /**
     * 首选成功聚合（First-Accepted）：
     * 按分支声明顺序返回首个为 {@link Outcome.Accepted} 的分支结果；
     * 若全部分支均未成功，则返回携带 {@code NO_APPLICABLE_BRANCH} 的 {@link Outcome.Skipped}。
     *
     * @return 首个成功的分支结果，或弃权结果
     */
    public Outcome<?> firstAccepted() {
        for (Outcome<?> outcome : outcomes) {
            if (outcome instanceof Outcome.Accepted) {
                return outcome;
            }
        }
        return Outcome.skipped(Reason.of("NO_APPLICABLE_BRANCH", "No parallel branch accepted"));
    }

    /**
     * 法定多数/配额聚合（Quorum）：
     * 当成功（Accepted）的分支数量达到或超过指定阈值 {@code required} 时返回成功 {@link Values}；
     * 否则返回错误码为 {@code QUORUM_NOT_REACHED} 的 {@link Outcome.Failed}。
     *
     * @param required 要求的最小成功分支数（必须满足 1 &lt;= required &lt;= 分支总数）
     * @return 达到法定配额则返回包含成功分支值的 {@link Values}，否则返回 Failed
     * @throws IllegalArgumentException 当 {@code required} 超出合法区间时抛出
     */
    public Outcome<Values> quorum(int required) {
        if (required < 1 || required > branches.size()) {
            throw new IllegalArgumentException("required quorum is out of range");
        }
        IdentityHashMap<Branch<?, ?>, Object> values = new IdentityHashMap<Branch<?, ?>, Object>();
        for (int index = 0; index < branches.size(); index++) {
            Outcome<?> outcome = outcomes.get(index);
            if (outcome instanceof Outcome.Accepted) {
                values.put(branches.get(index), ((Outcome.Accepted<?>) outcome).value());
            }
        }
        return values.size() >= required
                ? Outcome.accepted(new Values(values))
                : Outcome.<Values>failed(Failure.of("QUORUM_NOT_REACHED", "Parallel quorum was not reached"));
    }

    /**
     * 同质列表收集（Homogeneous Collect）：
     * 当所有分支均 Accepted 时，按声明顺序将其输出载荷收集为只读列表返回；
     * 只要存在任一非 Accepted 分支，则原样返回首个非 Accepted 结果。
     *
     * @return 收集后的载荷列表结果，或首个非 Accepted 结果
     */
    public Outcome<List<?>> homogeneousCollect() {
        List<Object> values = new ArrayList<Object>();
        for (Outcome<?> outcome : outcomes) {
            if (!(outcome instanceof Outcome.Accepted)) {
                return cast(outcome);
            }
            values.add(((Outcome.Accepted<?>) outcome).value());
        }
        return Outcome.<List<?>>accepted(Collections.unmodifiableList(values));
    }

    private int indexOf(Branch<?, ?> branch) {
        Objects.requireNonNull(branch, "branch must not be null");
        for (int index = 0; index < branches.size(); index++) {
            if (branches.get(index) == branch) {
                return index;
            }
        }
        throw new IllegalArgumentException("Branch does not belong to this result: " + branch.name());
    }

    @SuppressWarnings("unchecked")
    private static <T> Outcome<T> cast(Outcome<?> outcome) {
        return (Outcome<T>) outcome;
    }

    private static Validated validate(List<Branch<?, ?>> branches, List<Outcome<?>> outcomes) {
        Objects.requireNonNull(branches, "branches must not be null");
        Objects.requireNonNull(outcomes, "outcomes must not be null");
        if (branches.size() != outcomes.size()) {
            throw new IllegalArgumentException("branches and outcomes must have the same size");
        }

        List<Branch<?, ?>> branchCopy = new ArrayList<Branch<?, ?>>(branches.size());
        List<Outcome<?>> outcomeCopy = new ArrayList<Outcome<?>>(outcomes.size());
        IdentityHashMap<Branch<?, ?>, Boolean> seen = new IdentityHashMap<Branch<?, ?>, Boolean>();
        for (int index = 0; index < branches.size(); index++) {
            Branch<?, ?> branch = Objects.requireNonNull(
                    branches.get(index), "branch must not be null at index " + index);
            if (seen.put(branch, Boolean.TRUE) != null) {
                throw new IllegalArgumentException("duplicate branch token at index " + index + ": " + branch.name());
            }
            branchCopy.add(branch);
            outcomeCopy.add(Objects.requireNonNull(
                    outcomes.get(index), "outcome must not be null at index " + index));
        }
        return new Validated(branchCopy, outcomeCopy);
    }

    private static final class Validated {
        private final List<Branch<?, ?>> branches;
        private final List<Outcome<?>> outcomes;

        private Validated(List<Branch<?, ?>> branches, List<Outcome<?>> outcomes) {
            this.branches = Collections.unmodifiableList(branches);
            this.outcomes = Collections.unmodifiableList(outcomes);
        }
    }

    /**
     * 并行分支成功输出值的类型化只读查找表容器。
     */
    public static final class Values {
        private final Map<Branch<?, ?>, Object> values;

        private Values(IdentityHashMap<Branch<?, ?>, Object> values) {
            this.values = Collections.unmodifiableMap(new IdentityHashMap<Branch<?, ?>, Object>(values));
        }

        /**
         * 获取指定分支的成功输出值。
         *
         * @param branch 目标分支令牌，不能为 null
         * @param <T>    输出类型
         * @return 分支输出载荷
         * @throws NullPointerException  当 {@code branch} 为 null 时抛出
         * @throws IllegalStateException 当该分支并未成功（非 Accepted）或不存在于 Values 映射中时抛出
         */
        public <T> T get(Branch<?, T> branch) {
            Objects.requireNonNull(branch, "branch must not be null");
            Object value = values.get(branch);
            if (value == null) {
                throw new IllegalStateException("Branch was not accepted: " + branch.name());
            }
            @SuppressWarnings("unchecked") T typed = (T) value;
            return typed;
        }

        /**
         * 检查指定分支是否存在于成功输出值映射中。
         *
         * @param branch 目标分支令牌
         * @return 若存在返回 true，否则返回 false
         */
        public boolean contains(Branch<?, ?> branch) {
            return values.containsKey(branch);
        }
    }
}

