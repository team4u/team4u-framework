package com.team4u.framework.singleflight.config;

/**
 * Missing-rule behavior.
 *
 * @author jay.wu
 */
public enum RuleMissingPolicy {

    /**
     * Execute the loader directly and log a warning.
     */
    PASS_THROUGH,

    /**
     * Raise a configuration exception.
     */
    ERROR
}
