package com.team4u.framework.log.appender;

import com.team4u.framework.log.core.LogEvent;
import com.team4u.framework.log.core.LogSerializer;
import com.team4u.framework.log.core.PlainTextLogSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * SLF4J log appender that uses the serializer injected by its owning engine.
 */
public class Slf4jLogAppender implements SerializerAwareLogAppender {

    private final ConcurrentMap<String, Logger> loggerCache = new ConcurrentHashMap<>();
    private volatile LogSerializer serializer = new PlainTextLogSerializer();

    @Override
    public void append(LogEvent event) {
        String loggerName = event.getLoggerName();
        if (loggerName == null) {
            loggerName = LogEvent.class.getName();
        }
        Logger logger = loggerCache.computeIfAbsent(loggerName, LoggerFactory::getLogger);
        Level level = event.getLevel();

        if (!isLevelEnabled(logger, level)) {
            return;
        }

        String finalLogMsg = serializer.serialize(event);
        Level finalLevel = level != null ? level : Level.INFO;

        switch (finalLevel) {
            case INFO:
                logger.info(finalLogMsg);
                break;
            case ERROR:
                logger.error(finalLogMsg, event.getException());
                break;
            case DEBUG:
                logger.debug(finalLogMsg);
                break;
            case WARN:
                logger.warn(finalLogMsg);
                break;
            case TRACE:
                logger.trace(finalLogMsg);
                break;
            default:
                logger.info(finalLogMsg);
        }
    }

    @Override
    public void bindSerializer(LogSerializer serializer) {
        if (serializer != null) {
            this.serializer = serializer;
        }
    }

    private boolean isLevelEnabled(Logger logger, Level level) {
        if (level == null) {
            return true;
        }
        switch (level) {
            case INFO:
                return logger.isInfoEnabled();
            case ERROR:
                return logger.isErrorEnabled();
            case DEBUG:
                return logger.isDebugEnabled();
            case WARN:
                return logger.isWarnEnabled();
            case TRACE:
                return logger.isTraceEnabled();
            default:
                return logger.isInfoEnabled();
        }
    }
}
