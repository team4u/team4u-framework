package com.team4u.framework.flow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 按声明顺序的并行分支结果集合，提供按 token 的类型化查找与常用合并策略。
 * 传入 {@link JoinStrategy#join} 作为合并输入。
 */
public final class ParallelResults {
    private final List<Branch<?, ?>> branches;
    private final List<Outcome<?>> outcomes;

    ParallelResults(List<Branch<?, ?>> branches, List<Outcome<?>> outcomes) {
        this(validate(branches, outcomes));
    }

    private ParallelResults(Validated validated) {
        this.branches = validated.branches;
        this.outcomes = validated.outcomes;
    }

    /**
     * Creates an immutable, declaration-ordered view of parallel branch outcomes.
     */
    public static ParallelResults of(List<Branch<?, ?>> branches, List<Outcome<?>> outcomes) {
        return new ParallelResults(validate(branches, outcomes));
    }

    /** 按 token 查找分支结果（token 必须属于本集合）。 */
    public <T> Outcome<T> outcome(Branch<?, T> branch) {
        return cast(outcomes.get(indexOf(branch)));
    }

    /** 全部分支均 Accepted 时返回携带 Values 的 Accepted，否则返回首个非 Accepted 结果。 */
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

    /** 返回首个 Accepted 结果，无则 Skipped；异构分支集合下首个值类型未知，故诚实返回通配符。 */
    public Outcome<?> firstAccepted() {
        for (Outcome<?> outcome : outcomes) {
            if (outcome instanceof Outcome.Accepted) {
                return outcome;
            }
        }
        return Outcome.skipped(Reason.of("NO_APPLICABLE_BRANCH", "No parallel branch accepted"));
    }

    /** Accepted 分支数达到 required 即视为满足法定人数，否则失败。 */
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

    /** 同质收集：所有分支均 Accepted 时按序收集输出值，否则原样返回首个非 Accepted；
     *  异构集合下元素类型无法静态确认，故诚实返回通配符列表。 */
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

    public List<Branch<?, ?>> branches() {
        return branches;
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

    /** 按 token 收集的各分支输出值，仅包含 Accepted 分支。 */
    public static final class Values {
        private final Map<Branch<?, ?>, Object> values;

        private Values(IdentityHashMap<Branch<?, ?>, Object> values) {
            this.values = Collections.unmodifiableMap(new IdentityHashMap<Branch<?, ?>, Object>(values));
        }

        public <T> T get(Branch<?, T> branch) {
            Objects.requireNonNull(branch, "branch must not be null");
            Object value = values.get(branch);
            if (value == null) {
                throw new IllegalStateException("Branch was not accepted: " + branch.name());
            }
            @SuppressWarnings("unchecked") T typed = (T) value;
            return typed;
        }

        public boolean contains(Branch<?, ?> branch) {
            return values.containsKey(branch);
        }
    }
}
