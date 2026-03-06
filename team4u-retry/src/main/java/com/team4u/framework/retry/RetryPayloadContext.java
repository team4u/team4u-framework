package com.team4u.framework.retry;

public final class RetryPayloadContext {

    private final Phase phase;
    private final int executedAttempts;
    private RetryPayloadContext(Phase phase, int executedAttempts) {
        this.phase = phase;
        this.executedAttempts = executedAttempts;
    }

    public static RetryPayloadContext prepareIntent() {
        return new RetryPayloadContext(Phase.PREPARE_INTENT, 0);
    }

    public static RetryPayloadContext handoffToBackend(int executedAttempts) {
        if (executedAttempts < 1) {
            throw new IllegalArgumentException("executedAttempts must be >= 1 when handing off to backend");
        }
        return new RetryPayloadContext(Phase.HANDOFF_TO_BACKEND, executedAttempts);
    }

    public Phase getPhase() {
        return phase;
    }

    public int getExecutedAttempts() {
        return executedAttempts;
    }

    public enum Phase {
        PREPARE_INTENT,
        HANDOFF_TO_BACKEND
    }
}
