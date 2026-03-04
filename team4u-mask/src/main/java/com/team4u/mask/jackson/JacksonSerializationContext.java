package com.team4u.mask.jackson;

import com.fasterxml.jackson.databind.SerializerProvider;

/**
 * Jackson 序列化上下文工具
 */
public final class JacksonSerializationContext {

    /**
     * 防爆配置快照在 Jackson 属性中的 Key
     */
    public static final String ATTR_FINOPS_CONFIG_SNAPSHOT = "team4u.log.finopsConfig.snapshot";

    private static final int DEFAULT_MAX_STRING_LENGTH = 2000;

    private JacksonSerializationContext() {
    }

    /**
     * 解析最大字符串长度
     * <p>
     * 优先从 SerializerProvider 属性中尝试解析，如果失败则返回默认值。
     * 为了兼容性，它会尝试反射调用其 getMaxStringLength 方法（如果不依赖具体类）。
     *
     * @param provider SerializerProvider
     * @return 最大显示长度
     */
    public static int resolveMaxStringLength(SerializerProvider provider) {
        if (provider != null) {
            Object attribute = provider.getAttribute(ATTR_FINOPS_CONFIG_SNAPSHOT);
            if (attribute != null) {
                try {
                    // 使用反射获取配置，避免强依赖 team4u-log 模块的特定类
                    return (int) attribute.getClass().getMethod("getMaxStringLength").invoke(attribute);
                } catch (Exception ignored) {
                }
            }
        }
        return DEFAULT_MAX_STRING_LENGTH;
    }
}
