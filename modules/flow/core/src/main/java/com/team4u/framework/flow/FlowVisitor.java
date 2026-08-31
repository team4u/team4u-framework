package com.team4u.framework.flow;

/**
 * 流程结构访问者 SPI。覆盖 Invoke, Sequence, Route, Fallback, Parallel, Await, Control, Complete。
 */
public interface FlowVisitor<R> {
    R visitInvoke(NodeDescription node);
    R visitSequence(NodeDescription node);
    R visitRoute(NodeDescription node);
    R visitFallback(NodeDescription node);
    R visitParallel(NodeDescription node);
    R visitAwait(NodeDescription node);
    R visitControl(NodeDescription node);
    R visitComplete(NodeDescription node);
}
