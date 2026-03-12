package com.team4u.framework.retry.proxy.invocation;

import com.team4u.framework.retry.api.RecoverySpec;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 面向注解式/代理任务所需的特定恢复原数据。
 * 一般会序列化存放在 {@link RecoverySpec#getPayload()} 中。
 */
@Data
@Builder
public class InvocationRecoveryData {
    private String targetTypeName;
    private String targetBeanName;
    private String methodName;
    private List<InvocationArgSnapshot> args;
}
