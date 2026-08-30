package com.team4u.framework.flow;

/**
 * 流程内部节点合同。
 *
 * @author jay.wu
 */
interface FlowNode {

    String id();

    String path();

    String address();

    NodeKind kind();

    FlowResult<Object> execute(ExecutionContext context, Object input) throws Exception;

    NodeDescription describe();

    <R> R project(Flow.Projection<R> projection);
}
