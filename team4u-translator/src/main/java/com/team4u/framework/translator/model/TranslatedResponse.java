package com.team4u.framework.translator.model;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 最终翻译结果 (Immutable)
 * <p>
 * 渲染管线输出的最终对象，必须是不可变对象。
 */
@Getter
@AllArgsConstructor
public class TranslatedResponse {

    /**
     * 目标码
     */
    private final String code;

    /**
     * 目标文案
     */
    private final String message;

    /**
     * 链路追踪标识 (可空)
     */
    private final String traceId;
}
