package com.team4u.framework.flow.log;

import com.team4u.framework.mask.jackson.MaskedJson;

import java.util.Objects;

/**
 * 流程上下文安全脱敏格式化器。
 *
 * <p>将属性投影（{@link ContextProjector}）与 Jackson 掩码脱敏（{@link MaskedJson}）统一组装，
 * 确保单步实时日志与最终汇总树中的上下文格式严格对齐。</p>
 *
 * @author jay.wu
 */
public class ContextFormatter {

    private final ContextProjector projector;

    public ContextFormatter() {
        this(AnnotatedContextProjector.INSTANCE);
    }

    public ContextFormatter(ContextProjector projector) {
        this.projector = Objects.requireNonNull(projector, "projector must not be null");
    }

    /**
     * 格式化业务上下文对象：先执行属性投影过滤，再经由 MaskedJson 执行脱敏序列化。
     *
     * @param context 原始上下文对象
     * @return 脱敏后的 JSON 文本字符串
     */
    public String format(Object context) {
        if (context == null) {
            return "{}";
        }
        try {
            Object projected = projector.project(context);
            if (projected == null) {
                return "{}";
            }
            if (projected instanceof CharSequence) {
                return projected.toString();
            }
            return MaskedJson.toJsonStr(projected);
        } catch (Exception ex) {
            return String.valueOf(context);
        }
    }

    /**
     * 获取底层的上下文属性投影器。
     *
     * @return 投影器实例
     */
    public ContextProjector projector() {
        return projector;
    }
}
