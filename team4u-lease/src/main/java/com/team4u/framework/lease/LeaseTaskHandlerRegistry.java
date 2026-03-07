package com.team4u.framework.lease;

import java.util.Set;
import java.util.Optional;

/**
 * 租约任务处理器注册表接口。
 * <p>
 * 负责管理任务类型与 {@link LeaseTaskHandler} 之间的映射关系。
 */
public interface LeaseTaskHandlerRegistry {

    /**
     * 注册一个任务处理器。
     *
     * @param queue    处理器订阅的队列
     * @param taskType 业务任务类型
     * @param handler  处理器实例
     */
    void register(String queue, String taskType, LeaseTaskHandler handler);

    /**
     * 根据任务类型获取对应的处理器。
     *
     * @param queue    队列标识
     * @param taskType 任务类型标识
     * @return 匹配的处理器 Optional 容器
     */
    Optional<LeaseTaskHandler> get(String queue, String taskType);

    /**
     * 当前注册表声明的订阅集合。
     *
     * @return worker 可订阅的队列能力
     */
    Set<LeaseSubscription> subscriptions();
}
