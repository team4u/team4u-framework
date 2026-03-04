package com.team4u.mask.jackson;

import com.fasterxml.jackson.databind.module.SimpleModule;

/**
 * 通用脱敏 Jackson 模块
 * <p>
 * 提供开箱即用的 Jackson 无侵入脱敏能力。
 */
public class JacksonMaskModule extends SimpleModule {

    public JacksonMaskModule() {
        super("Team4uMaskModule");
        // 注册动态修饰器，处理 @Mask 注解和 MaskRuleRepository 中的规则
        this.setSerializerModifier(new DynamicMaskSerializerModifier());
    }
}
