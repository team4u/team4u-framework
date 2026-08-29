package com.team4u.framework.singleflight.api;

/**
 * Raised when a WAIT caller cannot receive a finished session in time.
 * <p>
 * Stack capture is disabled because timeout is expected under contention.
 * </p>
 *
 * @author jay.wu
 */
public class SingleFlightTimeoutException extends SingleFlightException {

    public SingleFlightTimeoutException(String message) {
        super(message);
    }

    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }
}
