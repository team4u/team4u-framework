package com.team4u.framework.flow.retry;

import com.team4u.framework.flow.definition.registry.FlowDefinitionExtension;
import com.team4u.framework.flow.definition.registry.FlowDefinitionRegistry;

/**
 * 重试治理模块在 FlowDefinitionRegistry 中的自动扩展 SPI 实现。
 *
 * @author jay.wu
 */
public class RetryFlowDefinitionExtension implements FlowDefinitionExtension {

    @Override
    public void contribute(FlowDefinitionRegistry.Builder builder) {
        builder.policyProvider(new RetryPolicyProvider("retry"));
        builder.policyProvider(new RetryPolicyProvider("flow.retry"));
    }
}
