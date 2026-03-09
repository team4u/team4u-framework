package com.team4u.framework.retry.backoff;

import com.team4u.framework.base.instance.DynamicInstanceProvider;
import com.team4u.framework.policy.core.KeyedPolicyRegistry;
import com.team4u.framework.policy.util.PolicyScanner;
import com.team4u.framework.retry.config.BackoffConfig;

/**
 * 退避策略注册表
 *
 * @author jay.wu
 */
public class BackoffRegistry extends KeyedPolicyRegistry<String, BackoffFactory> {

    private static final BackoffRegistry INSTANCE = new BackoffRegistry();

    private final DynamicInstanceProvider<BackoffConfig, BackoffConfig, Backoff> provider = DynamicInstanceProvider
            .createLru(1024, i -> i, config -> get(config.getType().toLowerCase())
                    .orElseGet(() -> get("fixed")
                            .orElseThrow(() -> new IllegalStateException("Missing fixed backoff factory")))
                    .create(config));

    public BackoffRegistry() {
        super(BackoffFactory.class);
        PolicyScanner.scanAndRegister(this);
    }

    public static BackoffRegistry global() {
        return INSTANCE;
    }

    public Backoff createBackoff(BackoffConfig config) {
        return provider.get(config);
    }
}
