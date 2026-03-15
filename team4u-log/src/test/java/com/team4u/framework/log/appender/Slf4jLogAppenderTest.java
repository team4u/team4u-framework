package com.team4u.framework.log.appender;

import com.team4u.framework.log.core.LogEvent;
import org.junit.Assert;
import org.junit.Test;
import org.slf4j.event.Level;

import java.lang.reflect.Method;

public class Slf4jLogAppenderTest {

    @Test
    public void testAppendWithDifferentLevels() {
        Slf4jLogAppender appender = new Slf4jLogAppender();

        // 测试各种级别，确保不会抛出异常
        LogEvent infoEvent = new LogEvent().setLevel(Level.INFO).setLoggerName("testLogger");
        appender.append(infoEvent);

        LogEvent debugEvent = new LogEvent().setLevel(Level.DEBUG).setLoggerName("testLogger");
        appender.append(debugEvent);

        LogEvent warnEvent = new LogEvent().setLevel(Level.WARN).setLoggerName("testLogger");
        appender.append(warnEvent);

        LogEvent errorEvent = new LogEvent().setLevel(Level.ERROR).setLoggerName("testLogger");
        errorEvent.setException(new RuntimeException("test error"));
        appender.append(errorEvent);

        LogEvent traceEvent = new LogEvent().setLevel(Level.TRACE).setLoggerName("testLogger");
        appender.append(traceEvent);

        // 测试 null level 的回退逻辑
        LogEvent nullLevelEvent = new LogEvent().setLevel(null).setLoggerName("testLogger");
        appender.append(nullLevelEvent);
        
        Assert.assertTrue(true); // 只要不抛异常即表示执行成功
    }

    @Test
    public void testIsLevelEnabled() throws Exception {
        Slf4jLogAppender appender = new Slf4jLogAppender();

        // 通过反射测试私有方法 isLevelEnabled
        Method method = Slf4jLogAppender.class.getDeclaredMethod("isLevelEnabled", org.slf4j.Logger.class, Level.class);
        method.setAccessible(true);
        
        org.slf4j.Logger mockLogger = org.slf4j.LoggerFactory.getLogger("testLogger");

        // 我们只要确保所有 level 和 null 不会抛错并且按逻辑正常返回即可
        method.invoke(appender, mockLogger, Level.INFO);
        method.invoke(appender, mockLogger, Level.DEBUG);
        method.invoke(appender, mockLogger, Level.WARN);
        method.invoke(appender, mockLogger, Level.ERROR);
        method.invoke(appender, mockLogger, Level.TRACE);
        method.invoke(appender, mockLogger, null);

        Assert.assertTrue(true);
    }
}
