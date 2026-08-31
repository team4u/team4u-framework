package com.team4u.framework.flow;

/**
 * Parallel 节点 wait-all 汇合后的显式类型化合并扩展点。
 * 接收声明顺序的分支结果，返回单个 Outcome。
 */
@FunctionalInterface
public interface JoinStrategy<O> {
    Outcome<O> join(ParallelResults results);
}
