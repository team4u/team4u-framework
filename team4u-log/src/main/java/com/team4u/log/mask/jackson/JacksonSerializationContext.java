package com.team4u.log.mask.jackson;

import com.fasterxml.jackson.databind.SerializerProvider;
import com.team4u.log.config.FinOpsConfigRepository;
import com.team4u.log.config.FinOpsConfigRepository.FinOpsConfig;

/**
 * Jackson 序列化上下文工具
 */
public final class JacksonSerializationContext {

    public static final String ATTR_FINOPS_CONFIG_SNAPSHOT = "team4u.log.finopsConfig.snapshot";

    private static final int DEFAULT_MAX_STRING_LENGTH = 2000;
    private static final int DEFAULT_MAX_LOG_LENGTH = 5000;

    private JacksonSerializationContext() {
    }

    public static FinOpsConfig resolveConfig(SerializerProvider provider) {
        if (provider != null) {
            Object attribute = provider.getAttribute(ATTR_FINOPS_CONFIG_SNAPSHOT);
            if (attribute instanceof FinOpsConfig) {
                return ensureConfig((FinOpsConfig) attribute);
            }
        }
        return ensureConfig(FinOpsConfigRepository.getInstance().get());
    }

    public static int resolveMaxStringLength(SerializerProvider provider) {
        FinOpsConfig config = resolveConfig(provider);
        return config != null ? config.getMaxStringLength() : DEFAULT_MAX_STRING_LENGTH;
    }

    public static int resolveMaxLogLength(FinOpsConfig snapshot) {
        FinOpsConfig config = ensureConfig(snapshot);
        return config != null ? config.getMaxLogLength() : DEFAULT_MAX_LOG_LENGTH;
    }

    private static FinOpsConfig ensureConfig(FinOpsConfig config) {
        return config != null ? config : new FinOpsConfig();
    }
}
