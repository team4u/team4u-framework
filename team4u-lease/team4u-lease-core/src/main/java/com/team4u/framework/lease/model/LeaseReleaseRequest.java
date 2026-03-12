package com.team4u.framework.lease.model;

import lombok.Builder;
import lombok.Getter;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 租约释放请求对象
 * <p>
 * 用于向调度系统释放当前持有的任务执行权，并定义任务下次可被抢占的时间。
 * 支持在释放的同时更新任务载荷（payload）及扩展属性。
 * attributes 采用 patch-only 语义：空 map 表示不修改现有属性。
 */
@Getter
public class LeaseReleaseRequest {

    /**
     * 下次可见的延迟毫秒数
     * <p>
     * 设置后，任务将在 (当前时间 + delayMillis) 之后重新进入可调度队列。
     */
    private final long delayMillis;

    /**
     * 附加属性快照（可选）
     * <p>
     * 用于在释放时同步更新任务的动态属性，通常用于保存中间处理状态。
     * 空 map 表示不修改现有属性。
     */
    private final Map<String, String> attributes;

    /**
     * 释放时同步更新的任务负载（可选）
     * <p>
     * 用于覆盖任务的业务数据载荷。
     */
    private final String payload;

    /**
     * 释放时记录的错误摘要（可选）
     * <p>
     * 若因业务异常释放，可记录详细的错误信息供审计。
     */
    private final String errorMessage;

    @Builder
    private LeaseReleaseRequest(long delayMillis,
                                Map<String, String> attributes,
                                String payload,
                                String errorMessage) {
        this.delayMillis = delayMillis;
        if (attributes == null) {
            this.attributes = Collections.emptyMap();
        } else {
            // 使用 LinkedHashMap 保持属性顺序，并包装为不可变集合
            this.attributes = Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
        }
        this.payload = payload;
        this.errorMessage = errorMessage;
    }

    /**
     * 快捷创建带有指定延迟的释放请求
     *
     * @param delayMillis 延迟可见时间（毫秒）
     * @return 释放请求实例
     */
    public static LeaseReleaseRequest of(long delayMillis) {
        return LeaseReleaseRequest.builder()

                .delayMillis(delayMillis)
                .build();
    }

    public static LeaseReleaseRequest of(long delayMillis, String errorMessage) {
        return LeaseReleaseRequest.builder()
                .delayMillis(delayMillis)
                .errorMessage(errorMessage)
                .build();
    }
}
