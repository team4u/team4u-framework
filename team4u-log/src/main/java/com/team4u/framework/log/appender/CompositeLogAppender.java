package com.team4u.framework.log.appender;

import com.team4u.framework.log.core.LogEvent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 复合日志追加器
 * <p>
 * 允许将一条日志同时分发给多个追加器执行输出。
 */
public class CompositeLogAppender implements LogAppender {

    private final List<LogAppender> appenders = new ArrayList<>();

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

    /**
     * 添加一个追加器
     */
    public void addAppender(LogAppender appender) {
        if (appender != null) {
            appenders.add(appender);
        }
    }

    /**
     * 获取所有内部追加器
     */
    public List<LogAppender> getAppenders() {
        return appenders;
    }
}
