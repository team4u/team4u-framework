package com.team4u.framework.flow;

import java.util.Objects;

/**
 * Parallel 分支的只读描述。
 */
public final class ParallelBranchDescription {
    private final String name;
    private final NodeDescription branch;

    public ParallelBranchDescription(String name, NodeDescription branch) {
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.branch = Objects.requireNonNull(branch, "branch must not be null");
    }

    public String name() {
        return name;
    }

    public NodeDescription branch() {
        return branch;
    }

    @Override
    public String toString() {
        return "ParallelBranchDescription[name=" + name + ", branch=" + branch + "]";
    }
}
