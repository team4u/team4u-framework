package com.team4u.framework.singleflight.api;

/**
 * Base exception of the singleflight component.
 * <p>
 * Exceptions raised by the loader itself are always rethrown unchanged; only
 * component decisions (conflict, timeout, reconstructed remote failures, and
 * configuration errors) use this hierarchy.
 * </p>
 *
 * @author jay.wu
 */
public class SingleFlightException extends RuntimeException {

    public SingleFlightException(String message) {
        super(message);
    }

    public SingleFlightException(String message, Throwable cause) {
        super(message, cause);
    }
}
