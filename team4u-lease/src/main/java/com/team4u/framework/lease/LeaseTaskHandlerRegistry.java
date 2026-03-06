package com.team4u.framework.lease;

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
     * @param handler 处理器实例
     */
    void register(LeaseTaskHandler handler);

    /**
     * 根据任务类型获取对应的处理器。
     *
     * @param taskType 任务类型标识
     * @return 匹配的处理器 Optional 容器
     */
    Optional<LeaseTaskHandler> get(String taskType);
}
