package com.team4u.framework.lease.model;

import lombok.Builder;
import lombok.Data;
import lombok.Singular;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 任务发布请求模型
 * <p>
 * 封装了发布任务所需的全部元数据，包括目标队列、任务类型以及执行参数。
 */
@Data
@Builder
public class LeasePublishRequest {

    /**
     * 目标任务队列名称
     */
    private final String queue;
    /**
     * 具体的任务业务类型
     */
    private final String taskType;
    /**
     * 任务执行载荷（通常为 JSON 格式的业务参数）
     */
    private final String payload;
    /**
     * 任务执行的期望延迟毫秒数（0 表示立即进入可获取状态）
     */
    private final long delayMillis;
    /**
     * 任务优先级，数值越大优先级越高
     */
    @Builder.Default
    private final int priority = 0;
    /**
     * 任务扩展属性，可用于存储非敏感的元数据
     */
    @Singular
    private final Map<String, String> attributes;

    /**
     * 获取不可变的扩展属性映射
     */
    public Map<String, String> getAttributes() {
        if (attributes == null) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }
}