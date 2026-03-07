package com.team4u.framework.lease;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 默认的租约任务处理器注册表，基于内存 ConcurrentMap 实现。
 */
public class DefaultLeaseTaskHandlerRegistry implements LeaseTaskHandlerRegistry {

    private final ConcurrentMap<String, ConcurrentMap<String, LeaseTaskHandler>> handlers =
            new ConcurrentHashMap<String, ConcurrentMap<String, LeaseTaskHandler>>();

    @Override
    public void register(String queue, String taskType, LeaseTaskHandler handler) {
        if (handler == null) {
            throw new IllegalArgumentException("handler must not be null");
        }
        if (queue == null || queue.trim().isEmpty()) {
            throw new IllegalArgumentException("queue must not be blank");
        }
        if (taskType == null || taskType.trim().isEmpty()) {
            throw new IllegalArgumentException("taskType must not be blank");
        }
        ConcurrentMap<String, LeaseTaskHandler> queueHandlers = handlers.computeIfAbsent(queue,
                ignored -> new ConcurrentHashMap<String, LeaseTaskHandler>());
        LeaseTaskHandler previous = queueHandlers.putIfAbsent(taskType, handler);
        if (previous != null) {
            throw new IllegalStateException("LeaseTaskHandler already registered. queue=" + queue + ", taskType=" + taskType);
        }
    }

    @Override
    public Optional<LeaseTaskHandler> get(String queue, String taskType) {
        if (queue == null || taskType == null) {
            return Optional.empty();
        }
        ConcurrentMap<String, LeaseTaskHandler> queueHandlers = handlers.get(queue);
        if (queueHandlers == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(queueHandlers.get(taskType));
    }

    @Override
    public Set<LeaseSubscription> subscriptions() {
        Set<LeaseSubscription> subscriptions = new LinkedHashSet<LeaseSubscription>();
        for (String queue : handlers.keySet()) {
            subscriptions.add(LeaseSubscription.builder().queue(queue).build());
        }
        return subscriptions;
    }
}
