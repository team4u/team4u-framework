package com.team4u.framework.flow;

import java.util.Objects;

/**
 * Route 分支的只读描述。
 */
public final class RouteCaseDescription {
    private final Object key;
    private final NodeDescription branch;

    public RouteCaseDescription(Object key, NodeDescription branch) {
        this.key = Objects.requireNonNull(key, "key must not be null");
        this.branch = Objects.requireNonNull(branch, "branch must not be null");
    }

    public Object key() {
        return key;
    }

    public NodeDescription branch() {
        return branch;
    }

    @Override
    public String toString() {
        return "RouteCaseDescription[key=" + key + ", branch=" + branch + "]";
    }
}
