package com.team4u.framework.flow;

import java.util.Objects;

/**
 * Route 节点的只读分支用例投影。
 */
public final class ExecutableRouteCase<R> {
    private final Object key;
    private final R branch;

    public ExecutableRouteCase(Object key, R branch) {
        this.key = Objects.requireNonNull(key, "key must not be null");
        this.branch = Objects.requireNonNull(branch, "branch must not be null");
    }

    public Object key() {
        return key;
    }

    public R branch() {
        return branch;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ExecutableRouteCase<?> that = (ExecutableRouteCase<?>) o;
        return key.equals(that.key) && branch.equals(that.branch);
    }

    @Override
    public int hashCode() {
        return Objects.hash(key, branch);
    }

    @Override
    public String toString() {
        return "ExecutableRouteCase[key=" + key + ", branch=" + branch + "]";
    }
}
