package com.team4u.framework.retry.backoff;

import com.team4u.framework.base.instance.DynamicInstanceProvider;
import com.team4u.framework.policy.core.KeyedPolicyRegistry;
import com.team4u.framework.policy.util.PolicyScanner;
import com.team4u.framework.retry.config.BackoffConfig;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 退避策略注册表
 *
 * @author jay.wu
 */
public class BackoffRegistry extends KeyedPolicyRegistry<String, BackoffFactory> {

    private static final BackoffRegistry INSTANCE = new BackoffRegistry();

    private final DynamicInstanceProvider<BackoffCacheKey, BackoffConfig, Backoff> provider = DynamicInstanceProvider
            .createLru(1024, BackoffCacheKey::toConfig, config -> get(config.getType())
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
        return provider.get(BackoffCacheKey.of(config));
    }

    @EqualsAndHashCode
    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    static final class BackoffCacheKey {
        private final String type;
        private final Map<String, Object> params;

        static BackoffCacheKey of(BackoffConfig config) {
            String type = config == null || config.getType() == null || config.getType().trim().isEmpty()
                    ? "fixed"
                    : config.getType().trim();
            Map<String, Object> params = immutableParams(config == null ? null : config.getParams());
            return new BackoffCacheKey(type, params);
        }

        BackoffConfig toConfig() {
            BackoffConfig config = new BackoffConfig();
            config.setType(type);
            config.setParams(params);
            return config;
        }

        private static Map<String, Object> immutableParams(Map<String, Object> source) {
            if (source == null || source.isEmpty()) {
                return Collections.emptyMap();
            }
            return Collections.unmodifiableMap(new LinkedHashMap<String, Object>(source));
        }
    }
}
