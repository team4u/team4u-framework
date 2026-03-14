package com.team4u.framework.log.appender;

import com.team4u.framework.log.core.LogEvent;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 复合日志追加器
 * <p>
 * 允许将一条日志同时分发给多个追加器执行输出。
 */
public class CompositeLogAppender implements LogAppender {

    private final CopyOnWriteArrayList<LogAppender> appenders = new CopyOnWriteArrayList<>();

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
        return Collections.unmodifiableList(appenders);
    }

    /**
     * 移除一个追加器
     */
    public boolean removeAppender(LogAppender appender) {
        return appender != null && appenders.remove(appender);
    }
}
