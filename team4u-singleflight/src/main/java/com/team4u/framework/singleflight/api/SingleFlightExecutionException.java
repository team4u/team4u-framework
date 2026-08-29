package com.team4u.framework.singleflight.api;

/**
 * Reconstructed loader failure delivered to WAIT callers.
 * <p>
 * Only the local loader caller receives the original business exception. Other
 * threads or instances read the failure envelope and get this exception; the
 * component does not promise to recreate the original exception across threads
 * or processes.
 * </p>
 *
 * @author jay.wu
 */
public class SingleFlightExecutionException extends SingleFlightException {

    public SingleFlightExecutionException(String message) {
        super(message);
    }

    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }
}
