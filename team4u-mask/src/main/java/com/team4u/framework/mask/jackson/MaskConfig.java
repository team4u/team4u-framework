package com.team4u.framework.mask.jackson;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 掩码模块序列化配置
 * <p>
 * 用于在 Jackson 序列化上下文中动态控制脱敏行为。
 */
@Data
@Accessors(chain = true)
public class MaskConfig {

    /**
     * 在 Jackson 属性中传递此配置的 Key
     */
    public static final String ATTR_KEY = "team4u.mask.config";

    /**
     * 字符串最大截断长度。小于等于 0 表示不限制。
     * （默认不限制，除非宿主环境要求截断）
     */
    private int maxStringLength = -1;
}
