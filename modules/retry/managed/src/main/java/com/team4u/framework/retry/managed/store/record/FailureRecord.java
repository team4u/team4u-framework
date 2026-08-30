package com.team4u.framework.retry.managed.store.record;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * 失败异常记录。
 */
@Data
@Builder(toBuilder = true)
public class FailureRecord {
    /**
     * 异常或者失败代码
     */
    private String errorCode;
    /**
     * 异常详细信息
     */
    private String errorMessage;
    /**
     * 失败发生的时间
     */
    private Instant failedAt;
    /**
     * 最终失败前已执行的总尝试次数
     */
    private Integer attempts;
}
