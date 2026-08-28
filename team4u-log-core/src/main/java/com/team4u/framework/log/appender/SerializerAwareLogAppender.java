package com.team4u.framework.log.appender;

import com.team4u.framework.log.core.LogSerializer;

/**
 * Appender contract for appenders that serialize events at output time.
 */
public interface SerializerAwareLogAppender extends LogAppender {

    /**
     * Binds the serializer owned by the current {@code LogEngine}.
     *
     * @param serializer non-null serializer used for this appender's output
     */
    void bindSerializer(LogSerializer serializer);
}
