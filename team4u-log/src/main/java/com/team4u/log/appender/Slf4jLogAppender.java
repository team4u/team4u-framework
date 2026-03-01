package com.team4u.log.appender;

import com.team4u.log.core.LogEngine;
import com.team4u.log.core.LogEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

/**
 * SLF4J 日志追加器
 * <p>
 * 将结构化日志事件通过 SLF4J 打印。
 */
public class Slf4jLogAppender implements LogAppender {

    @Override
    public void append(LogEvent event) {
        Logger logger = LoggerFactory.getLogger(event.getLoggerName());
        Level level = event.getLevel();

        // 预检查日志级别，避免不必要的 JSON 序列化
        if (!isLevelEnabled(logger, level)) {
            return;
        }

        // 确定输出时执行序列化和脱敏处理
        String finalLogMsg = LogEngine.getInstance().toJson(event);

        switch (level) {
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
            default:
                logger.trace(finalLogMsg);
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
            default:
                return logger.isTraceEnabled();
        }
    }
}
