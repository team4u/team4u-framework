package com.team4u.framework.lease.runtime;

import lombok.Builder;
import lombok.Getter;
import lombok.Singular;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 任务执行上下文
 * <p>
 * 封装了租约任务在执行期间的所有上下文信息，包括任务元数据、业务载荷及扩展属性。
 * 同时提供了与运行时环境交互的接口（如手动触发心跳续约）。
 */
@Getter
public class LeaseExecutionContext {

    /**
     * 任务唯一标识符
     */
    private final String taskId;
    /**
     * 任务所属的逻辑任务分组名称
     */
    private final String taskGroup;
    /**
     * 业务定义的任务类型，用于路由到对应的处理器
     */
    private final String taskType;
    /**
     * 业务数据载荷（通常为 JSON 序列化字符串）
     */
    private final String payload;
    /**
     * 该任务被投递给 Worker 的累计次数
     */
    private final int deliveryCount;
    /**
     * 该任务历史执行失败的累计次数
     */
    private final int failureCount;
    /**
     * 任务携带的扩展属性快照
     */
    private final Map<String, String> attributes;
    /**
     * 任务最初创建的时间戳（毫秒）
     */
    private final long createdAtMillis;
    /**
     * 任务最近一次可见（可被抢占）的时间戳（毫秒）
     */
    private final long visibleAtMillis;
    /**
     * 当前持有租约的过期截止时间戳（毫秒）
     */
    private final long leaseExpiresAtMillis;
    /**
     * 触发即时心跳续约的回调句柄
     */
    private final Runnable heartbeatRequester;

    @Builder
    protected LeaseExecutionContext(String taskId,
                                    String taskGroup,
                                    String taskType,
                                    String payload,
                                    int deliveryCount,
                                    int failureCount,
                                    @Singular Map<String, String> attributes,
                                    long createdAtMillis,
                                    long visibleAtMillis,
                                    long leaseExpiresAtMillis,
                                    Runnable heartbeatRequester) {
        this.taskId = taskId;
        this.taskGroup = taskGroup;
        this.taskType = taskType;
        this.payload = payload;
        this.deliveryCount = deliveryCount;
        this.failureCount = failureCount;
        if (attributes == null) {
            this.attributes = Collections.emptyMap();
        } else {
            this.attributes = Collections.unmodifiableMap(new LinkedHashMap<String, String>(attributes));
        }
        this.createdAtMillis = createdAtMillis;
        this.visibleAtMillis = visibleAtMillis;
        this.leaseExpiresAtMillis = leaseExpiresAtMillis;
        this.heartbeatRequester = heartbeatRequester;
    }

    /**
     * 主动发起一次心跳请求
     * <p>
     * 当业务代码预计后续处理仍需较长时间，且不希望等待工作者自动心跳时，可以调用此方法立即续约。
     */
    public void requestHeartbeat() {
        if (heartbeatRequester != null) {
            heartbeatRequester.run();
        }
    }
}
