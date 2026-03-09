package com.team4u.framework.retry.recovery;

import lombok.Builder;
import lombok.Data;

/**
 * 恢复执行时的上下文信息。
 */
@Data
@Builder
public class RecoveryContext {
    private String taskId;
    private int attempt;
}
