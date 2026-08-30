package com.team4u.framework.flow;

import java.util.Objects;

/**
 * Parallel 节点的只读分支投影。
 */
public final class ExecutableParallelBranch<R> {
    private final Branch<?, ?> token;
    private final R branchPlan;

    public ExecutableParallelBranch(Branch<?, ?> token, R branchPlan) {
        this.token = Objects.requireNonNull(token, "token must not be null");
        this.branchPlan = Objects.requireNonNull(branchPlan, "branchPlan must not be null");
    }

    public Branch<?, ?> token() {
        return token;
    }

    public R branchPlan() {
        return branchPlan;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ExecutableParallelBranch<?> that = (ExecutableParallelBranch<?>) o;
        return token.equals(that.token) && branchPlan.equals(that.branchPlan);
    }

    @Override
    public int hashCode() {
        return Objects.hash(token, branchPlan);
    }

    @Override
    public String toString() {
        return "ExecutableParallelBranch[token=" + token + ", branchPlan=" + branchPlan + "]";
    }
}
