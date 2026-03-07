package com.team4u.framework.lease;

import java.util.Optional;

/**
 * 租约任务查询服务接口
 */
public interface LeaseQueryService {

    /**
     * 根据任务 ID 获取任务详情记录
     *
     * @param taskId 全局唯一的任务 ID
     * @return 包含任务详情的 Optional 容器，若不存在则为空
     */
    Optional<LeaseTaskRecord> get(String taskId);

    /**
     * 根据条件分页查询任务记录
     *
     * @param request 包含过滤条件、分页参数及排序规则的查询请求
     * @return 分页结果包装，包含当前页数据及总数统计
     */
    LeaseTaskPage list(LeaseQueryRequest request);
}
