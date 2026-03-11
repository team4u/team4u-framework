package com.team4u.framework.lease.handler;

import com.team4u.framework.lease.model.LeaseSubscription;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 默认的租约任务处理器注册表实现
 * <p>
 * 基于内存 {@link ConcurrentHashMap} 实现，支持多线程并发注册和查询。
 * 采用两级嵌套 Map 结构：{@code Map<queue, Map<taskType, handler>>}，
 * 实现 queue 到 taskType 再到 handler 的精确路由。
 * <p>
 * <b>线程安全：</b>
 * 所有注册和查询操作均为线程安全，适用于运行时动态注册场景。
 * <p>
 * <b>冲突处理：</b>
 * 同一 queue + taskType 组合不允许重复注册，重复注册将抛出 {@link IllegalStateException}。
 * 建议在应用启动阶段完成所有处理器的预注册。
 */
public class DefaultLeaseTaskHandlerRegistry implements LeaseTaskHandlerRegistry {

    private final ConcurrentMap<String, ConcurrentMap<String, LeaseTaskHandler>> handlers = new ConcurrentHashMap<>();

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

        ConcurrentMap<String, LeaseTaskHandler> queueHandlers = handlers.computeIfAbsent(
                queue,
                ignored -> new ConcurrentHashMap<>()
        );

        LeaseTaskHandler previous = queueHandlers.putIfAbsent(taskType, handler);
        if (previous != null) {
            throw new IllegalStateException("LeaseTaskHandler already registered. " +
                    "queue=" + queue + ", taskType=" + taskType);
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
        Set<LeaseSubscription> subscriptions = new LinkedHashSet<>();
        for (String queue : handlers.keySet()) {
            subscriptions.add(LeaseSubscription.builder().queue(queue).build());
        }
        return subscriptions;
    }
}