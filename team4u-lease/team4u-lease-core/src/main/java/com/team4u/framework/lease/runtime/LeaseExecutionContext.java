package com.team4u.framework.lease.runtime;

import com.team4u.framework.lease.api.LeaseRuntimeClient;
import com.team4u.framework.lease.model.LeaseHandle;
import lombok.Builder;
import lombok.Data;
import lombok.Singular;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 任务执行上下文
 * <p>
 * 封装了任务在处理期间所需的全部信息，并提供了与运行时（如主动心跳续约）交互的接口。
 */
@Data
public class LeaseExecutionContext {

    /**
     * 任务 ID
     */
    private final String taskId;
    /**
     * 所属队列
     */
    private final String queue;
    /**
     * 业务定义的任务类型
     */
    private final String taskType;
    /**
     * 业务载荷（JSON 等序列化内容）
     */
    private final String payload;
    /**
     * 累计投递次数
     */
    private final int deliveryCount;
    /**
     * 累计失败次数
     */
    private final int failureCount;
    /**
     * 扩展参数
     */
    private final Map<String, String> attributes;
    /**
     * 任务创建时间
     */
    private final long createdAtMillis;
    /**
     * 任务最近可见时间
     */
    private final long visibleAtMillis;
    /**
     * 当前租约过期截止时间
     */
    private final long leaseExpiresAtMillis;
    /**
     * 主动触发心跳的操作回调
     */
    private final Runnable heartbeatRequester;
    /**
     * 运行时租约客户端接口
     */
    private final LeaseRuntimeClient runtimeClient;
    /**
     * 租约操作句柄
     */
    private final LeaseHandle handle;

    @Builder
    private LeaseExecutionContext(String taskId,
            String queue,
            String taskType,
            String payload,
            int deliveryCount,
            int failureCount,
            @Singular Map<String, String> attributes,
            long createdAtMillis,
            long visibleAtMillis,
            long leaseExpiresAtMillis,
            Runnable heartbeatRequester,
            LeaseRuntimeClient runtimeClient,
            LeaseHandle handle) {
        this.taskId = taskId;
        this.queue = queue;
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
        this.runtimeClient = runtimeClient;
        this.handle = handle;
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
