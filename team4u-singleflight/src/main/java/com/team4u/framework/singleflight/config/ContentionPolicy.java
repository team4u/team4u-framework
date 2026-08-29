package com.team4u.framework.singleflight.config;

/**
 * Contention behavior when another caller holds the singleflight lock.
 *
 * @author jay.wu
 */
public enum ContentionPolicy {
    WAIT,
    FAIL_FAST,
    FALLBACK
}
