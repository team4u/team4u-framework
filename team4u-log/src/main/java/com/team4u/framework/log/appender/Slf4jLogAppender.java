package com.team4u.framework.log.appender;

import com.team4u.framework.log.core.LogEngine;
import com.team4u.framework.log.core.LogEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * SLF4J 日志追加器
 * <p>
 * 将结构化日志事件通过 SLF4J 打印。
 */
public class Slf4jLogAppender implements LogAppender {

    private final ConcurrentMap<String, Logger> loggerCache = new ConcurrentHashMap<>();

    @Override
    public void append(LogEvent event) {
        String loggerName = event.getLoggerName();
        if (loggerName == null) {
            loggerName = LogEngine.class.getName(); // 默认兜底名称
        }
        Logger logger = loggerCache.computeIfAbsent(loggerName, LoggerFactory::getLogger);
        Level level = event.getLevel();

        // 预检查日志级别，避免不必要的 JSON 序列化
        if (!isLevelEnabled(logger, level)) {
            return;
        }

        // 确定输出时执行序列化和脱敏处理
        String finalLogMsg = LogEngine.getInstance().toJson(event);

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

    private boolean isLevelEnabled(Logger logger, Level level) {
        if (level == null)
            return true;
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