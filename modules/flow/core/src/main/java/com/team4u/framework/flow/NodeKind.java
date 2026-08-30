package com.team4u.framework.flow;

/**
 * 流程节点类型。
 *
 * @author jay.wu
 */
public enum NodeKind {
    STEP,
    TAP,
    GUARD,
    CHOOSE,
    SUBFLOW,
    RECOVER,
    ENSURE,
    SEQUENCE
}
