package com.team4u.framework.mask.jackson;

import com.fasterxml.jackson.databind.module.SimpleModule;

/**
 * 通用脱敏 Jackson 模块
 * <p>
 * 提供开箱即用的 Jackson 无侵入脱敏能力。
 */
public class JacksonMaskModule extends SimpleModule {

    public JacksonMaskModule() {
        super("Team4uMaskModule");
        this.setSerializerModifier(new DynamicMaskSerializerModifier());
    }
}
