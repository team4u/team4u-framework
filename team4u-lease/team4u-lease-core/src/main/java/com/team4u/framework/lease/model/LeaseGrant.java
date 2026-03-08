package com.team4u.framework.lease.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 租约授权结果
 * <p>
 * 当工作者成功抢占到任务后，后端返回该对象，包含任务的所有业务载荷以及执行凭证。
 */
public class LeaseGrant {

    /**
     * 运行时操作句柄，包含用于状态回写的凭证
     */
    private final LeaseHandle handle;
    /**
     * 任务唯一 ID
     */
    private final String taskId;
    /**
     * 任务所属队列
     */
    private final String queue;
    /**
     * 任务业务类型
     */
    private final String taskType;
    /**
     * 任务业务载荷
     */
    private final String payload;
    /**
     * 累计投递次数（包含当前次）
     */
    private final int deliveryCount;
    /**
     * 累计失败次数
     */
    private final int failureCount;
    /**
     * 扩展属性
     */
    private final Map<String, String> attributes;
    /**
     * 任务创建时间戳（毫秒）
     */
    private final long createdAtMillis;
    /**
     * 任务最近一次可见时间戳（毫秒）
     */
    private final long visibleAtMillis;
    /**
     * 当前租约的到期截止时间戳（毫秒）
     */
    private final long leaseExpiresAtMillis;
    /**
     * 持有该租约的工作者 ID
     */
    private final String workerId;
    /**
     * 租约幂等令牌，用于后端校验当前工作者是否仍持有该任务的合法控制权
     */
    private final String leaseToken;

    public LeaseGrant(String taskId,
                      String workerId,
                      String leaseToken,
                      String queue,
                      String taskType,
                      String payload,
                      int deliveryCount,
                      int failureCount,
                      Map<String, String> attributes,
                      long createdAtMillis,
                      long visibleAtMillis,
                      long leaseExpiresAtMillis) {
        this.handle = new LeaseHandle(taskId, workerId, leaseToken);
        this.taskId = taskId;
        this.queue = queue;
        this.taskType = taskType;
        this.payload = payload;
        this.deliveryCount = deliveryCount;
        this.failureCount = failureCount;
        this.attributes = attributes == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, String>(attributes));
        this.createdAtMillis = createdAtMillis;
        this.visibleAtMillis = visibleAtMillis;
        this.leaseExpiresAtMillis = leaseExpiresAtMillis;
        this.workerId = workerId;
        this.leaseToken = leaseToken;
    }

    public LeaseHandle getHandle() {
        return handle;
    }

    public String getTaskId() {
        return taskId;
    }

    public String getQueue() {
        return queue;
    }

    public String getTaskType() {
        return taskType;
    }

    public String getPayload() {
        return payload;
    }

    public int getDeliveryCount() {
        return deliveryCount;
    }

    public int getFailureCount() {
        return failureCount;
    }

    public Map<String, String> getAttributes() {
        return attributes;
    }

    public long getCreatedAtMillis() {
        return createdAtMillis;
    }

    public long getVisibleAtMillis() {
        return visibleAtMillis;
    }

    public long getLeaseExpiresAtMillis() {
        return leaseExpiresAtMillis;
    }
}
