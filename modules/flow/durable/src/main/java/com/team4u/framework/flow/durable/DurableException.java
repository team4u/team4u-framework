package com.team4u.framework.flow.durable;

import java.util.Objects;

/** Stable error boundary for durable commands and persistence. */
public final class DurableException extends RuntimeException {
    public enum Error {
        INVALID_DEFINITION,
        INVALID_CONFIGURATION,
        EXECUTION_EXISTS,
        EXECUTION_NOT_FOUND,
        FLOW_MISMATCH,
        FORMAT_MISMATCH,
        FRAME_MISMATCH,
        CODEC_FAILURE,
        STORE_FAILURE,
        REVISION_CONFLICT,
        LIFECYCLE_MISMATCH,
        RESUME_POINT_MISMATCH,
        RESUME_SIGNAL_CONFLICT,
        ASYNC_EXECUTOR_MISSING
    }

    private final Error error;

    public DurableException(Error error, String message) {
        super(message);
        this.error = Objects.requireNonNull(error, "error must not be null");
    }

    public DurableException(Error error, String message, Throwable cause) {
        super(message, cause);
        this.error = Objects.requireNonNull(error, "error must not be null");
    }

    public Error error() {
        return error;
    }
}
