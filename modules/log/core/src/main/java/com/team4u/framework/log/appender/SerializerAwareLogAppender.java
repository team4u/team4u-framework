package com.team4u.framework.log.appender;

import com.team4u.framework.log.core.LogSerializer;

/**
 * Appender contract for appenders that serialize events at output time.
 */
public interface SerializerAwareLogAppender extends LogAppender {

    /**
     * Binds the serializer owned by the current {@code LogEngine}.
     *
     * <p>Implementations must be nonblocking and MUST NOT call back into any engine or
     * global appender mutation API. Binding may execute while global ownership and the
     * owning engine's local appender synchronization are held.
     *
     * @param serializer non-null serializer used for this appender's output
     */
    void bindSerializer(LogSerializer serializer);
}
