package com.team4u.mask.jackson;

import com.fasterxml.jackson.databind.SerializerProvider;

/**
 * Jackson 序列化上下文工具
 */
public final class JacksonSerializationContext {

    private static final MaskConfig DEFAULT_CONFIG = new MaskConfig();

    private JacksonSerializationContext() {
    }

    /**
     * 获取当前上下文中的脱敏配置 (高性能，无反射)
     *
     * @param provider SerializerProvider
     * @return 掩码配置对象，若未注入则返回默认配置
     */
    public static MaskConfig getConfig(SerializerProvider provider) {
        if (provider != null) {
            Object attr = provider.getAttribute(MaskConfig.ATTR_KEY);
            if (attr instanceof MaskConfig) {
                return (MaskConfig) attr;
            }
        }
        return DEFAULT_CONFIG;
    }
}
