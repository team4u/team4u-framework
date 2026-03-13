package com.team4u.framework.lease.handler;

import com.team4u.framework.lease.model.LeaseTaskGroupSubscription;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 默认的租约任务处理器注册表实现
 * <p>
 * 基于内存 {@link ConcurrentHashMap} 实现，支持多线程并发注册和查询。
 * 采用两级嵌套 Map 结构：{@code Map<taskGroup, Map<taskType, handler>>}，
 * 实现 taskGroup 到 taskType 再到 handler 的精确路由。
 * <p>
 * <b>线程安全：</b>
 * 所有注册和查询操作均为线程安全，适用于运行时动态注册场景。
 * <p>
 * <b>冲突处理：</b>
 * 同一 taskGroup + taskType 组合不允许重复注册，重复注册将抛出 {@link IllegalStateException}。
 * 建议在应用启动阶段完成所有处理器的预注册。
 */
public class DefaultLeaseTaskHandlerRegistry implements LeaseTaskHandlerRegistry {

    private final ConcurrentMap<String, ConcurrentMap<String, LeaseTaskHandler>> handlers = new ConcurrentHashMap<>();

    @Override
    public void register(String taskGroup, String taskType, LeaseTaskHandler handler) {
        if (handler == null) {
            throw new IllegalArgumentException("handler must not be null");
        }
        if (taskGroup == null || taskGroup.trim().isEmpty()) {
            throw new IllegalArgumentException("taskGroup must not be blank");
        }
        if (taskType == null || taskType.trim().isEmpty()) {
            throw new IllegalArgumentException("taskType must not be blank");
        }

        ConcurrentMap<String, LeaseTaskHandler> groupHandlers = handlers.computeIfAbsent(
                taskGroup,
                ignored -> new ConcurrentHashMap<>()
        );

        LeaseTaskHandler previous = groupHandlers.putIfAbsent(taskType, handler);
        if (previous != null) {
            throw new IllegalStateException("LeaseTaskHandler already registered. " +
                    "taskGroup=" + taskGroup + ", taskType=" + taskType);
        }
    }

    @Override
    public Optional<LeaseTaskHandler> get(String taskGroup, String taskType) {
        if (taskGroup == null || taskType == null) {
            return Optional.empty();
        }
        ConcurrentMap<String, LeaseTaskHandler> groupHandlers = handlers.get(taskGroup);
        if (groupHandlers == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(groupHandlers.get(taskType));
    }

    @Override
    public Set<LeaseTaskGroupSubscription> subscriptions() {
        Set<LeaseTaskGroupSubscription> subscriptions = new LinkedHashSet<>();
        for (String taskGroup : handlers.keySet()) {
            subscriptions.add(LeaseTaskGroupSubscription.builder().taskGroup(taskGroup).build());
        }
        return subscriptions;
    }
}
