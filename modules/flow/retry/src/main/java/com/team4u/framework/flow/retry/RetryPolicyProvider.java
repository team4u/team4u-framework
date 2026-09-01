package com.team4u.framework.flow.retry;

import com.team4u.framework.flow.definition.registry.PolicyBinding;
import com.team4u.framework.flow.definition.registry.PolicyDescriptor;
import com.team4u.framework.flow.definition.registry.PolicyProvider;
import com.team4u.framework.flow.definition.util.ConfigMapReader;
import com.team4u.framework.retry.common.backoff.Backoff;
import com.team4u.framework.retry.common.backoff.Backoffs;

import java.time.Duration;
import java.util.Map;

/**
 * 流程重试策略 DSL 配置提供器（Retry Policy Provider）。
 *
 * <p>根据 DSL 声明的策略 ID 与配置参数（如 {@code maxAttempts}、{@code backoff}），动态构造 {@link FlowRetryPolicy} 实例与策略绑定。</p>
 *
 * @author jay.wu
 */
public class RetryPolicyProvider implements PolicyProvider {

    private final PolicyDescriptor descriptor;

    public RetryPolicyProvider(String id) {
        this.descriptor = PolicyDescriptor.builder()
                .id(id)
                .contract(FlowRetryPolicy.class)
                .persistent(true)
                .build();
    }

    public RetryPolicyProvider() {
        this("retry");
    }

    @Override
    public PolicyDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public PolicyBinding create(Map<String, Object> configuration) {
        ConfigMapReader reader = ConfigMapReader.of(configuration);
        Integer maxAttempts = reader.getInt("maxAttempts", null, "max-attempts");
        Duration backoffDuration = reader.getDuration("backoff");
        Backoff backoff = backoffDuration != null ? Backoffs.fixed(backoffDuration.toMillis()) : null;

        FlowRetryPolicy<Object> policy = FlowRetryPolicy.<Object>builder()
                .maxAttempts(maxAttempts)
                .backoff(backoff)
                .build();

        return PolicyBinding.builder()
                .instance(policy)
                .persistent(true)
                .build();
    }
}
