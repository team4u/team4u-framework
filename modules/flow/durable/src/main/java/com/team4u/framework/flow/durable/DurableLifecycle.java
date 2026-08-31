package com.team4u.framework.flow.durable;

/** Durable execution lifecycle, separate from business outcomes. */
public enum DurableLifecycle {
    ACTIVE,
    SUSPENDED,
    COMPLETED,
    CANCELLED
}
