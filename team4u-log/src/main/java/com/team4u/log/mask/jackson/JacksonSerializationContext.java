package com.team4u.log.mask.jackson;

import com.fasterxml.jackson.databind.SerializerProvider;
import com.team4u.log.config.LogConfigManager;
import com.team4u.log.config.LogDynamicConfig;

/**
 * Jackson 序列化上下文工具
 */
public final class JacksonSerializationContext {

    public static final String ATTR_LOG_CONFIG_SNAPSHOT = "team4u.log.dynamicConfig.snapshot";

    private static final int DEFAULT_MAX_STRING_LENGTH = 2000;
    private static final int DEFAULT_MAX_LOG_LENGTH = 5000;

    private JacksonSerializationContext() {
    }

    public static LogDynamicConfig resolveConfig(SerializerProvider provider) {
        if (provider != null) {
            Object attribute = provider.getAttribute(ATTR_LOG_CONFIG_SNAPSHOT);
            if (attribute instanceof LogDynamicConfig) {
                return ensureConfig((LogDynamicConfig) attribute);
            }
        }
        return ensureConfig(LogConfigManager.getInstance().getCurrentConfig());
    }

    public static int resolveMaxStringLength(SerializerProvider provider) {
        LogDynamicConfig config = resolveConfig(provider);
        LogDynamicConfig.FinOpsConfig finOpsConfig = config.getFinOpsConfig();
        return finOpsConfig != null ? finOpsConfig.getMaxStringLength() : DEFAULT_MAX_STRING_LENGTH;
    }

    public static int resolveMaxLogLength(LogDynamicConfig snapshot) {
        LogDynamicConfig config = ensureConfig(snapshot);
        LogDynamicConfig.FinOpsConfig finOpsConfig = config.getFinOpsConfig();
        return finOpsConfig != null ? finOpsConfig.getMaxLogLength() : DEFAULT_MAX_LOG_LENGTH;
    }

    private static LogDynamicConfig ensureConfig(LogDynamicConfig config) {
        LogDynamicConfig safeConfig = config != null ? config : new LogDynamicConfig();
        if (safeConfig.getFinOpsConfig() == null) {
            safeConfig.setFinOpsConfig(new LogDynamicConfig.FinOpsConfig());
        }
        return safeConfig;
    }
}
