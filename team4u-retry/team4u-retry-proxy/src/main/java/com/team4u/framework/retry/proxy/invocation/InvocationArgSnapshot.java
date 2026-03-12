package com.team4u.framework.retry.proxy.invocation;

import lombok.Builder;
import lombok.Data;

/**
 * 单个方法参数的持久化快照。
 */
@Data
@Builder
public class InvocationArgSnapshot {
    private String typeName;
    private String serializedValue;
    private boolean ignored;
}
