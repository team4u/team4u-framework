package com.team4u.framework.singleflight.config;

/**
 * Invalid-key behavior.
 *
 * @author jay.wu
 */
public enum InvalidKeyPolicy {

    /**
     * Raise a configuration exception.
     */
    ERROR,

    /**
     * Execute the loader without coordination.
     */
    PASS_THROUGH
}
