package com.team4u.framework.flow;

import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.util.Objects;

/**
 * 动态条件路由（Route）单个分支的只读结构描述。
 *
 * @author team4u
 */
@Getter
@Accessors(fluent = true)
@ToString
public final class RouteCaseDescription {
    /** 分支匹配判别键。 */
    private final Object key;
    /** 命中该分支时执行的目标节点描述树。 */
    private final NodeDescription branch;

    /**
     * 构造路由分支描述。
     *
     * @param key    分支匹配键，不能为 null
     * @param branch 目标节点描述树，不能为 null
     * @throws NullPointerException 当入参为 null 时抛出
     */
    public RouteCaseDescription(Object key, NodeDescription branch) {
        this.key = Objects.requireNonNull(key, "key must not be null");
        this.branch = Objects.requireNonNull(branch, "branch must not be null");
    }
}

