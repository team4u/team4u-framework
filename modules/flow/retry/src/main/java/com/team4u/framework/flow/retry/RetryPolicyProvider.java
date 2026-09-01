package com.team4u.framework.flow.retry;

import com.team4u.framework.flow.definition.registry.PolicyBinding;
import com.team4u.framework.flow.definition.registry.PolicyDescriptor;
import com.team4u.framework.flow.definition.registry.PolicyProvider;
import com.team4u.framework.flow.definition.type.TypeCodecs;
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
        Integer maxAttempts = null;
        Object attemptsVal = configuration.get("maxAttempts");
        if (attemptsVal == null) {
            attemptsVal = configuration.get("max-attempts");
        }
        if (attemptsVal instanceof Number) {
            maxAttempts = ((Number) attemptsVal).intValue();
        } else if (attemptsVal instanceof String) {
            try {
                maxAttempts = Integer.parseInt((String) attemptsVal);
            } catch (NumberFormatException ignored) { }
        }

        Backoff backoff = null;
        Object backoffVal = configuration.get("backoff");
        if (backoffVal instanceof Duration) {
            backoff = Backoffs.fixed(((Duration) backoffVal).toMillis());
        } else if (backoffVal instanceof String) {
            try {
                Duration dur = TypeCodecs.parseDuration((String) backoffVal);
                backoff = Backoffs.fixed(dur.toMillis());
            } catch (Exception ignored) { }
        } else if (backoffVal instanceof Number) {
            backoff = Backoffs.fixed(((Number) backoffVal).longValue());
        }

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
