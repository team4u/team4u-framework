package com.team4u.framework.flow;

/**
 * 节点执行上下文：暴露当前执行与节点的只读元数据。
 *
 * @author jay.wu
 */
public interface StepContext {

    /**
     * 流程定义 ID。
     */
    String flowId();

    /**
     * 本次执行的唯一 ID。
     */
    String executionId();

    /**
     * 当前节点的 ID。
     */
    String nodeId();

    /**
     * 当前节点的诊断路径。
     */
    String nodePath();

    /**
     * 针对当前节点调用位置的不透明幂等调用 ID。
     * 在同一次执行中对同一节点位置具有确定性，重放/重试时保持不变。
     */
    String invocationId();
}
