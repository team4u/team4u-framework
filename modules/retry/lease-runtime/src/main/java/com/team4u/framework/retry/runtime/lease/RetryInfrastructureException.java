package com.team4u.framework.retry.runtime.lease;

import com.team4u.framework.lease.runtime.TaskInfrastructureException;

/**
 * Marks a retry infrastructure failure that happened outside business recovery execution.
 *
 * <p>Business handlers must not be treated as failed merely because a durable record could not be
 * encoded or decoded. Throwing this explicit runtime exception keeps that failure mode separate
 * from a {@link com.team4u.framework.retry.managed.recovery.RecoveryHandler} failure.</p>
 */

public class RetryInfrastructureException extends TaskInfrastructureException {

    public RetryInfrastructureException(String message, Throwable cause) {
        super(message, cause);
    }
}
