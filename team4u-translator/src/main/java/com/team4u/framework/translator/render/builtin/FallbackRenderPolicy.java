package com.team4u.framework.translator.render.builtin;

import com.team4u.framework.base.util.StringUtil;
import com.team4u.framework.translator.model.RenderContext;
import com.team4u.framework.translator.render.RenderPolicy;

/**
 * 内置：兜底渲染器
 * <p>
 * 如果目标配置未指定或为空，则采用原始数据进行兜底。
 */
public class FallbackRenderPolicy implements RenderPolicy {

    @Override
    public int priority() {
        return LOWEST;
    }

    @Override
    public boolean supports(RenderContext context) {
        return true;
    }

    @Override
    public void render(RenderContext context) {
        // 如果最终码为空字符串或 null，则使用 source 的原始 code 覆盖
        if (StringUtil.isEmpty(context.getFinalCode())) {
            context.setFinalCode(context.getSource().getCode());
        }

        // 如果最终文案为空字符串或 null，则使用 source 的原始 message 覆盖
        if (StringUtil.isEmpty(context.getFinalMessage())) {
            context.setFinalMessage(context.getSource().getMessage());
        }
    }
}
