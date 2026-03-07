package com.team4u.framework.lease;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 默认的租约任务处理器注册表，基于内存 ConcurrentMap 实现。
 */
public class DefaultLeaseTaskHandlerRegistry implements LeaseTaskHandlerRegistry {

    private final ConcurrentMap<String, LeaseTaskHandler> handlers = new ConcurrentHashMap<String, LeaseTaskHandler>();

    @Override
    public void register(LeaseTaskHandler handler) {
        if (handler == null) {
            throw new IllegalArgumentException("handler must not be null");
        }
        String key = handler.key();
        if (key == null || key.trim().isEmpty()) {
            throw new IllegalArgumentException("handler.key() must not be blank");
        }
        handlers.put(key, handler);
    }

    @Override
    public Optional<LeaseTaskHandler> get(String taskType) {
        if (taskType == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(handlers.get(taskType));
    }
}
