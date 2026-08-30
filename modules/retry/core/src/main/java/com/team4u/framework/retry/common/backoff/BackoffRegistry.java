package com.team4u.framework.retry.common.backoff;

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
                    .orElseThrow(() -> new IllegalArgumentException("Unsupported backoff type: " + config.getType()))
                    .create(config));

    public BackoffRegistry() {
        super(BackoffFactory.class);
        PolicyScanner.scanAndRegister(this);
    }

    public static BackoffRegistry global() {
        return INSTANCE;
    }

    static String normalizeType(String type) {
        return type == null || type.trim().isEmpty() ? "fixed" : type.trim();
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
            String type = normalizeType(config == null ? null : config.getType());
            Map<String, Object> params = immutableParams(config == null ? null : config.getParams());
            return new BackoffCacheKey(type, params);
        }

        private static Map<String, Object> immutableParams(Map<String, Object> source) {
            if (source == null || source.isEmpty()) {
                return Collections.emptyMap();
            }
            return Collections.unmodifiableMap(new LinkedHashMap<String, Object>(source));
        }

        BackoffConfig toConfig() {
            BackoffConfig config = new BackoffConfig();
            config.setType(type);
            config.setParams(params);
            return config;
        }
    }
}
