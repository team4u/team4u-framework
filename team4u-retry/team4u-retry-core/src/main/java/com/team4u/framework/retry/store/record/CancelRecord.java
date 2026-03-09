package com.team4u.framework.retry.store.record;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * 取消记录。
 */
@Data
@Builder
public class CancelRecord {
    /**
     * 取消原因
     */
    private String reason;
    /**
     * 取消操作时间
     */
    private Instant cancelledAt;
}
