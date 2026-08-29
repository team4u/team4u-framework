package com.team4u.framework.singleflight.api;

/**
 * Raised when a non-waiting caller loses the singleflight lock race.
 * <p>
 * The exception carries no stack trace: conflict is a normal high-frequency
 * outcome, so construction must stay inexpensive.
 * </p>
 *
 * @author jay.wu
 */
public class SingleFlightConflictException extends SingleFlightException {

    public SingleFlightConflictException(String message) {
        super(message);
    }

    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }
}
