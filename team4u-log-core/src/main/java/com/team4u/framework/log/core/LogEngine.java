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
import java.util.function.UnaryOperator;
/**
 * Core logging engine with an explicitly owned serializer and interceptor set.
 */
public final class LogEngine {

    private static final AtomicReference<LogEngine> GLOBAL = new AtomicReference<>(new Builder().build());
    private static final Object GLOBAL_MONITOR = new Object();

    private final LogSerializer serializer;
    private final LogInterceptorManager interceptorManager;
    private final Object appenderMonitor = new Object();
    private volatile LogAppender appender = new Slf4jLogAppender();

    private LogEngine(Builder builder) {
        this.serializer = builder.serializer != null
                ? builder.serializer
                : new PlainTextLogSerializer();
        this.interceptorManager = new LogInterceptorManager();
        for (LogInterceptor interceptor : builder.interceptors) {
            interceptorManager.register(interceptor);
        }
        bindAppenderSerializer(appender);
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
            LogAppender transferred = previous.snapshotAppender();
            engine.setLocalAppender(transferred);
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
            LogAppender transferred = current.snapshotAppender();
            previous.setLocalAppender(transferred);
            GLOBAL.set(previous);
            return true;
        }
    }

    public static LogAppender setGlobalAppender(LogAppender appender) {
        return updateGlobalAppender(current -> appender);
    }

    public static boolean compareAndSetGlobalAppender(LogAppender expect, LogAppender update) {
        synchronized (GLOBAL_MONITOR) {
            LogEngine engine = GLOBAL.get();
            return engine.compareAndSetLocalAppender(expect, update);
        }
    }

    public static LogAppender updateGlobalAppender(UnaryOperator<LogAppender> transform) {
        Objects.requireNonNull(transform, "transform");
        synchronized (GLOBAL_MONITOR) {
            LogEngine engine = GLOBAL.get();
            return engine.updateLocalAppender(transform);
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
        setLocalAppender(appender);
    }

    public boolean compareAndSetAppender(LogAppender expect, LogAppender update) {
        return compareAndSetLocalAppender(expect, update);
    }

    /**
     * Resets core-owned state while retaining this engine's explicit serializer and injected interceptors.
     */
    public void reset() {
        RuntimeException firstError = null;
        try {
            setAppender(new Slf4jLogAppender());
        } catch (RuntimeException error) {
            firstError = error;
        }
        try {
            interceptorManager.resetCore();
        } catch (RuntimeException error) {
            firstError = addSuppressed(firstError, error);
        }
        try {
            serializer.reset();
        } catch (RuntimeException error) {
            firstError = addSuppressed(firstError, error);
        }
        if (firstError != null) {
            throw firstError;
        }
    }

    private RuntimeException addSuppressed(RuntimeException firstError, RuntimeException error) {
        if (firstError == null) {
            return error;
        }
        if (firstError != error) {
            firstError.addSuppressed(error);
        }
        return firstError;
    }

    private LogAppender snapshotAppender() {
        synchronized (appenderMonitor) {
            return appender;
        }
    }

    private void setLocalAppender(LogAppender appender) {
        LogAppender next = appender != null ? appender : new Slf4jLogAppender();
        synchronized (appenderMonitor) {
            this.appender = next;
            bindAppenderSerializer(next);
        }
    }

    private boolean compareAndSetLocalAppender(LogAppender expect, LogAppender update) {
        synchronized (appenderMonitor) {
            if (appender != expect) {
                return false;
            }
            setLocalAppender(update);
            return true;
        }
    }

    private LogAppender updateLocalAppender(UnaryOperator<LogAppender> transform) {
        synchronized (appenderMonitor) {
            LogAppender previous = appender;
            LogAppender next = transform.apply(previous);
            if (next != previous) {
                setLocalAppender(next);
            }
            return previous;
        }
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

    private void bindAppenderSerializer(LogAppender appender) {
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
