package com.team4u.framework.log.core;

import com.team4u.framework.log.appender.LogAppender;
import com.team4u.framework.log.appender.SerializerAwareLogAppender;
import com.team4u.framework.log.appender.Slf4jLogAppender;
import com.team4u.framework.log.pipeline.LogInterceptor;
import com.team4u.framework.log.pipeline.LogInterceptorManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Core logging engine with an explicitly owned serializer and interceptor set.
 */
public final class LogEngine {

    private static final AtomicReference<LogEngine> GLOBAL = new AtomicReference<>(new Builder().build());
    private static final Object GLOBAL_MONITOR = new Object();

    private final LogSerializer serializer;
    private final LogInterceptorManager interceptorManager;
    private volatile LogAppender appender = new Slf4jLogAppender();

    private LogEngine(Builder builder) {
        this.serializer = builder.serializer != null
                ? builder.serializer
                : new PlainTextLogSerializer();
        this.interceptorManager = new LogInterceptorManager();
        for (LogInterceptor interceptor : builder.interceptors) {
            interceptorManager.register(interceptor);
        }
        bindAppender(appender);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static LogEngine getInstance() {
        return GLOBAL.get();
    }

    /**
     * Installs an engine globally and transfers the current output appender.
     *
     * @param engine replacement engine, never null
     * @return previous global engine
     */
    public static LogEngine install(LogEngine engine) {
        Objects.requireNonNull(engine, "engine");
        synchronized (GLOBAL_MONITOR) {
            LogEngine previous = GLOBAL.get();
            if (previous == engine) {
                return previous;
            }
            engine.setAppender(previous.appender);
            GLOBAL.set(engine);
            return previous;
        }
    }

    /**
     * Restores a previous engine only when the caller still owns the expected engine.
     *
     * @param expected engine that must currently be installed
     * @param previous engine to restore, never null
     * @return true when ownership was retained and restoration succeeded
     */
    public static boolean restore(LogEngine expected, LogEngine previous) {
        Objects.requireNonNull(previous, "previous");
        synchronized (GLOBAL_MONITOR) {
            LogEngine current = GLOBAL.get();
            if (current != expected) {
                return false;
            }
            previous.setAppender(current.appender);
            GLOBAL.set(previous);
            return true;
        }
    }

    public LogInterceptorManager getInterceptorManager() {
        return interceptorManager;
    }

    public LogSerializer getSerializer() {
        return serializer;
    }

    public LogAppender getAppender() {
        return appender;
    }

    public void setAppender(LogAppender appender) {
        LogAppender next = appender != null ? appender : new Slf4jLogAppender();
        this.appender = next;
        bindAppender(next);
    }

    /**
     * Resets core state while retaining this engine's explicit serializer.
     */
    public void reset() {
        setAppender(new Slf4jLogAppender());
        interceptorManager.reset();
        serializer.reset();
    }

    public void processAndOutput(LogEvent event) {
        boolean passed = interceptorManager.execute(event);
        if (!passed || event.isSuppressed()) {
            return;
        }

        LogAppender currentAppender = this.appender;
        if (currentAppender != null) {
            currentAppender.append(event);
        }
    }

    public String toJson(LogEvent event) {
        return serializer.serialize(event);
    }

    private void bindAppender(LogAppender appender) {
        if (appender instanceof SerializerAwareLogAppender) {
            ((SerializerAwareLogAppender) appender).bindSerializer(serializer);
        }
    }

    public static final class Builder {

        private LogSerializer serializer;
        private final List<LogInterceptor> interceptors = new ArrayList<>();

        private Builder() {
        }

        public Builder serializer(LogSerializer serializer) {
            this.serializer = serializer;
            return this;
        }

        public Builder interceptor(LogInterceptor interceptor) {
            if (interceptor != null) {
                this.interceptors.add(interceptor);
            }
            return this;
        }

        public Builder interceptors(Iterable<? extends LogInterceptor> interceptors) {
            if (interceptors != null) {
                for (LogInterceptor interceptor : interceptors) {
                    interceptor(interceptor);
                }
            }
            return this;
        }

        public LogEngine build() {
            return new LogEngine(this);
        }
    }
}
