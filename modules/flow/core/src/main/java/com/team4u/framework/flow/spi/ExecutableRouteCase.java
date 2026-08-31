package com.team4u.framework.flow.spi;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.util.Objects;

/**
 * 动态条件路由（Route）单条分支的强类型可执行投影视图。
 *
 * @param <R> 目标执行拓扑节点的泛型类型
 * @author jay.wu
 */
@Getter
@Accessors(fluent = true)
@EqualsAndHashCode
@ToString
public final class ExecutableRouteCase<R> {
    /** 分支判别键。 */
    private final Object key;
    /** 投影后的分支执行拓扑树。 */
    private final R branch;

    /**
     * 构造路由分支执行投影。
     *
     * @param key    分支匹配键，不能为 null
     * @param branch 分支执行计划树，不能为 null
     * @throws NullPointerException 当入参为 null 时抛出
     */
    public ExecutableRouteCase(Object key, R branch) {
        this.key = Objects.requireNonNull(key, "key must not be null");
        this.branch = Objects.requireNonNull(branch, "branch must not be null");
    }
}

