package com.team4u.framework.flow.durable;

/**
 * 持久化流程生命周期状态。
 *
 * @author jay.wu
 */
public enum DurableLifecycle {
    ACTIVE,
    COMPLETED,
    STOPPED,
    FAILED,
    CANCELLED
}
