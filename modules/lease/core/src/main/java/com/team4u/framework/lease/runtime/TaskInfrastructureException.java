package com.team4u.framework.lease.runtime;

/**
 * Marks an infrastructure failure around task handler execution.
 *
 * <p>When a handler throws this exception, the worker abandons the current lease without a
 * terminal or retry write-back. The lease is left RUNNING and becomes recoverable only after it
 * expires. Business handler failures must continue to use ordinary exceptions.</p>
 */
public class TaskInfrastructureException extends RuntimeException {

    public TaskInfrastructureException(String message, Throwable cause) {
        super(message, cause);
    }
}
