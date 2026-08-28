package com.team4u.framework.log.core;

/**
 * Raw, unmasked plain-text serializer. Core is provider-free and emits no masking policy.
 */
public final class PlainTextLogSerializer implements LogSerializer {

    @Override
    public String serialize(LogEvent event) {
        try {
            return String.valueOf(event);
        } catch (RuntimeException e) {
            return fallback(event, e);
        }
    }

    private String fallback(LogEvent event, RuntimeException e) {
        String action = null;
        try {
            action = event.getAction();
        } catch (RuntimeException ignored) {
            // Keep the fallback path defensive even for a broken event implementation.
        }
        return "LogEvent serialization failed: class="
                + event.getClass().getName()
                + ", action=" + action
                + ", reason=" + e;
    }

    @Override
    public void reset() {
        // This implementation is stateless.
    }
}
