package com.team4u.framework.lease.jdbc;

import com.team4u.framework.lease.enums.LeaseTaskFailureReason;
import com.team4u.framework.lease.enums.LeaseTaskOutcome;
import com.team4u.framework.lease.enums.LeaseTaskState;
import com.team4u.framework.lease.model.LeaseGrant;
import com.team4u.framework.lease.model.LeaseTaskRecord;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 租赁任务数据库实体类
 *
 * @author jay.wu
 */
@Getter
@Builder(toBuilder = true)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class LeaseTaskEntity {

    /**
     * 任务唯一标识
     */
    private final String taskId;

    /**
     * 任务所属队列
     */
    private final String queue;

    /**
     * 任务类型
     */
    private final String taskType;

    /**
     * 业务负载数据
     */
    private final String payload;
    /**
     * 业务幂等键。
     */
    private final String businessKey;

    /**
     * 任务当前状态
     */
    private final LeaseTaskState state;
    private final LeaseTaskOutcome outcome;
    private final LeaseTaskFailureReason failureReason;

    /**
     * 任务优先级
     */
    private final int priority;

    /**
     * 投递/执行次数
     */
    private final int deliveryCount;

    /**
     * 失败次数
     */
    private final int failureCount;

    /**
     * 当前持有租约的工作节点 ID
     */
    private final String workerId;

    /**
     * 租约令牌
     */
    private final String leaseToken;

    /**
     * 租约到期毫秒时间戳
     */
    private final long leaseExpiresAtMillis;

    /**
     * 任务下次可见（可被消费）的毫秒时间戳
     */
    private final long visibleAtMillis;

    /**
     * 创建毫秒时间戳
     */
    private final long createdAtMillis;

    /**
     * 最后更新毫秒时间戳
     */
    private final long updatedAtMillis;

    /**
     * 行版本号，用于乐观锁。
     */
    private final long version;

    /**
     * 最后一次执行的错误摘要
     */
    private final String errorMessage;

    /**
     * 扩展属性
     */
    private final Map<String, String> attributes;

    /**
     * 获取不可变的属性 Map
     */
    public Map<String, String> getAttributes() {
        if (attributes == null) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<String, String>(attributes));
    }

    /**
     * 转换为租约确认对象
     */
    public LeaseGrant toGrant() {
        return LeaseGrant.builder()
                .taskId(taskId)
                .workerId(workerId)
                .leaseToken(leaseToken)
                .queue(queue)
                .taskType(taskType)
                .payload(payload)
                .deliveryCount(deliveryCount)
                .failureCount(failureCount)
                .attributes(attributes)
                .createdAtMillis(createdAtMillis)
                .visibleAtMillis(visibleAtMillis)
                .leaseExpiresAtMillis(leaseExpiresAtMillis)
                .build();
    }

    /**
     * 转换为任务记录对象（用于管理后台展示）
     */
    public LeaseTaskRecord toRecord() {
        return LeaseTaskRecord.builder()
                .taskId(taskId)
                .queue(queue)
                .taskType(taskType)
                .payload(payload)
                .businessKey(businessKey)
                .state(state)
                .outcome(outcome)
                .failureReason(failureReason)
                .workerId(workerId)
                .priority(priority)
                .deliveryCount(deliveryCount)
                .failureCount(failureCount)
                .createdAtMillis(createdAtMillis)
                .visibleAtMillis(visibleAtMillis)
                .leaseExpiresAtMillis(leaseExpiresAtMillis)
                .errorMessage(errorMessage)
                .attributes(attributes)
                .build();
    }
}
