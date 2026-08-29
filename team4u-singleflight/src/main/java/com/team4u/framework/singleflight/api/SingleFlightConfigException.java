package com.team4u.framework.singleflight.api;

/**
 * Raised when a rule cannot be loaded or when its behavior is not usable for
 * the current execution request (for example a primitive fallback value).
 *
 * @author jay.wu
 */
public class SingleFlightConfigException extends SingleFlightException {

    public SingleFlightConfigException(String message) {
        super(message);
    }

    public SingleFlightConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
