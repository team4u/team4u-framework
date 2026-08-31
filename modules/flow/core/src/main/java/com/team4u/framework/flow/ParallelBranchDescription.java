package com.team4u.framework.flow;

import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.util.Objects;

/**
 * 结构化并行（Parallel）单条分支的只读结构描述。
 *
 * @author jay.wu
 */
@Getter
@Accessors(fluent = true)
@ToString
public final class ParallelBranchDescription {
    /** 分支名称。 */
    private final String name;
    /** 分支目标节点描述树。 */
    private final NodeDescription branch;

    /**
     * 构造并行分支描述。
     *
     * @param name   分支名称，不能为 null
     * @param branch 目标节点描述树，不能为 null
     * @throws NullPointerException 当入参为 null 时抛出
     */
    public ParallelBranchDescription(String name, NodeDescription branch) {
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.branch = Objects.requireNonNull(branch, "branch must not be null");
    }
}

