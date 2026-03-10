package com.team4u.framework.retry.backoff;

import com.team4u.framework.base.instance.DynamicInstanceProvider;
import com.team4u.framework.policy.core.KeyedPolicyRegistry;
import com.team4u.framework.policy.util.PolicyScanner;
import com.team4u.framework.retry.config.BackoffConfig;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

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

    static final class BackoffCacheKey {
        private final String type;
        private final Map<String, Object> params;

        private BackoffCacheKey(String type, Map<String, Object> params) {
            this.type = type;
            this.params = params;
        }

        static BackoffCacheKey of(BackoffConfig config) {
            String type = config == null || config.getType() == null || config.getType().trim().isEmpty()
                    ? "fixed"
                    : config.getType().trim();
            Map<String, Object> params = normalizeMap(config == null ? null : config.getParams());
            return new BackoffCacheKey(type, params);
        }

        BackoffConfig toConfig() {
            BackoffConfig config = new BackoffConfig();
            config.setType(type);
            config.setParams(params);
            return config;
        }

        private static Map<String, Object> normalizeMap(Map<String, Object> source) {
            if (source == null || source.isEmpty()) {
                return Collections.emptyMap();
            }
            Map<String, Object> normalized = new TreeMap<String, Object>();
            for (Map.Entry<String, Object> entry : source.entrySet()) {
                normalized.put(entry.getKey(), normalizeValue(entry.getValue()));
            }
            return Collections.unmodifiableMap(new LinkedHashMap<String, Object>(normalized));
        }

        private static Object normalizeValue(Object value) {
            if (value == null) {
                return null;
            }
            if (value instanceof Map<?, ?>) {
                Map<String, Object> nested = new LinkedHashMap<String, Object>();
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                    nested.put(String.valueOf(entry.getKey()), normalizeValue(entry.getValue()));
                }
                return normalizeMap(nested);
            }
            if (value instanceof Collection<?>) {
                List<Object> normalized = new ArrayList<Object>();
                for (Object element : (Collection<?>) value) {
                    normalized.add(normalizeValue(element));
                }
                return Collections.unmodifiableList(normalized);
            }
            if (value.getClass().isArray()) {
                int length = Array.getLength(value);
                List<Object> normalized = new ArrayList<Object>(length);
                for (int i = 0; i < length; i++) {
                    normalized.add(normalizeValue(Array.get(value, i)));
                }
                return Collections.unmodifiableList(normalized);
            }
            return value;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof BackoffCacheKey)) {
                return false;
            }
            BackoffCacheKey that = (BackoffCacheKey) o;
            return Objects.equals(type, that.type) && Objects.equals(params, that.params);
        }

        @Override
        public int hashCode() {
            return Objects.hash(type, params);
        }
    }
}
