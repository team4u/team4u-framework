package com.team4u.framework.log.appender;

import com.team4u.framework.log.core.LogEvent;
import com.team4u.framework.log.core.LogSerializer;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Composite appender that propagates serializer rebinding to current and future children.
 */
public class CompositeLogAppender implements SerializerAwareLogAppender {

    private final CopyOnWriteArrayList<LogAppender> appenders = new CopyOnWriteArrayList<>();
    private volatile LogSerializer serializer;

    public CompositeLogAppender(LogAppender... appenders) {
        if (appenders != null) {
            this.appenders.addAll(Arrays.asList(appenders));
        }
    }

    @Override
    public void append(LogEvent event) {
        for (LogAppender appender : appenders) {
            appender.append(event);
        }
    }

    @Override
    public void bindSerializer(LogSerializer serializer) {
        if (serializer == null) {
            return;
        }
        this.serializer = serializer;
        for (LogAppender appender : appenders) {
            bindChild(appender, serializer);
        }
    }

    /**
     * Adds an appender and binds the serializer currently bound to this composite.
     */
    public void addAppender(LogAppender appender) {
        if (appender == null) {
            return;
        }
        appenders.add(appender);
        LogSerializer currentSerializer = serializer;
        if (currentSerializer != null) {
            bindChild(appender, currentSerializer);
        }
    }

    public List<LogAppender> getAppenders() {
        return Collections.unmodifiableList(appenders);
    }

    public boolean removeAppender(LogAppender appender) {
        return appender != null && appenders.remove(appender);
    }

    private void bindChild(LogAppender appender, LogSerializer serializer) {
        if (appender instanceof SerializerAwareLogAppender) {
            ((SerializerAwareLogAppender) appender).bindSerializer(serializer);
        }
    }
}
