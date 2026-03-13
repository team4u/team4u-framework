package com.team4u.framework.lease.model;

import com.team4u.framework.lease.enums.LeaseTaskFailureReason;
import com.team4u.framework.lease.enums.LeaseTaskOutcome;
import com.team4u.framework.lease.enums.LeaseTaskState;
import lombok.Builder;
import lombok.Data;
import lombok.Singular;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 任务查询请求模型
 * <p>
 * 封装了分页查询任务记录时的所有过滤条件和分页参数。
 * 支持按任务分组、任务类型、生命周期状态、执行结果、失败原因及 Worker ID 进行多维度筛选。
 * 该请求通常用于管理后台或运维 API 获取任务列表。
 */
@Data
@Builder
public class LeaseQueryRequest {

    /**
     * 按任务分组名称过滤
     */
    private final String taskGroup;
    /**
     * 按任务类型过滤
     */
    private final String taskType;
    /**
     * 按任务生命周期状态过滤（多选）
     */
    @Singular
    private final Set<LeaseTaskState> states;
    /**
     * 按任务执行结果过滤（多选），仅对 CLOSED 状态任务有效
     */
    @Singular
    private final Set<LeaseTaskOutcome> outcomes;
    /**
     * 按失败原因过滤（多选），仅对 FAILED 结果的任务有效
     */
    @Singular
    private final Set<LeaseTaskFailureReason> failureReasons;
    /**
     * 按持有租约的 Worker ID 过滤
     */
    private final String workerId;
    /**
     * 页码（从 0 开始）
     */
    @Builder.Default
    private final int page = 0;
    /**
     * 每页期望返回的记录条数，默认 50 条
     */
    @Builder.Default
    private final int pageSize = 50;

    public Set<LeaseTaskState> getStates() {
        if (states == null) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(states));
    }

    public Set<LeaseTaskOutcome> getOutcomes() {
        if (outcomes == null) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(new LinkedHashSet<LeaseTaskOutcome>(outcomes));
    }

    public Set<LeaseTaskFailureReason> getFailureReasons() {
        if (failureReasons == null) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(failureReasons));
    }
}
