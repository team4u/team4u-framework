package com.team4u.framework.singleflight.config;

/**
 * Store failure behavior. Explicit rule values are honored; when a rule omits
 * the field, WAIT and FALLBACK default to PASS_THROUGH and FAIL_FAST defaults
 * to FAIL_CLOSED.
 *
 * @author jay.wu
 */
public enum StoreFailurePolicy {

    /**
     * Skip coordination and execute the loader directly.
     */
    PASS_THROUGH,

    /**
     * Stop the execution with a component exception.
     */
    FAIL_CLOSED
}
