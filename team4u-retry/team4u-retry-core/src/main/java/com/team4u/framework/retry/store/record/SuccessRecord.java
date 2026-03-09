package com.team4u.framework.retry.store.record;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * 成功记录。
 */
@Data
@Builder
public class SuccessRecord {
    /**
     * 成功完成的时间
     */
    private Instant succeededAt;
}
