package com.team4u.framework.retry.managed.store.record;

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
    /**
     * 成功前已执行的总尝试次数
     */
    private Integer attempts;
}
