package com.team4u.framework.flow;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.util.Objects;

/**
 * 结构化并行（Parallel）单条分支的强类型可执行投影视图。
 *
 * @param <R> 目标执行拓扑节点的泛型类型
 * @author team4u
 */
@Getter
@Accessors(fluent = true)
@EqualsAndHashCode
@ToString
public final class ExecutableParallelBranch<R> {
    /** 分支令牌标识。 */
    private final Branch<?, ?> token;
    /** 投影后的分支执行拓扑树。 */
    private final R branchPlan;

    /**
     * 构造并行分支执行投影。
     *
     * @param token      分支令牌，不能为 null
     * @param branchPlan 分支执行计划树，不能为 null
     * @throws NullPointerException 当入参为 null 时抛出
     */
    public ExecutableParallelBranch(Branch<?, ?> token, R branchPlan) {
        this.token = Objects.requireNonNull(token, "token must not be null");
        this.branchPlan = Objects.requireNonNull(branchPlan, "branchPlan must not be null");
    }
}

