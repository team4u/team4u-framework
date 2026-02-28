package com.team4u.framework.translator.render;

import com.team4u.framework.policy.api.ContextPolicy;
import com.team4u.framework.translator.model.RenderContext;

/**
 * 渲染器 SPI 接口
 * <p>
 * 继承自 team4u-policy 的 ContextPolicy，用于在管线中处理 RenderContext。
 */
public interface RenderPolicy extends ContextPolicy<RenderContext> {

    /**
     * 执行渲染逻辑
     *
     * @param context 渲染管线流转上下文
     */
    void render(RenderContext context);
}
