package com.team4u.framework.translator.render.builtin;

import cn.hutool.core.util.StrUtil;
import com.team4u.framework.translator.model.ErrorDef;
import com.team4u.framework.translator.model.RenderContext;
import com.team4u.framework.translator.render.RenderPolicy;

/**
 * 内置：多语言国际化渲染器
 * <p>
 * 位于兜底渲染之后，模板渲染之前。
 * 负责通过 ErrorDef 的 i18nKey 寻找对应的多语言文案模板。
 * 若无多语言文案配置、无此键或寻找失败，则静默退回使用 defaultMsg。
 */
public class I18nRenderPolicy implements RenderPolicy {

    @Override
    public int priority() {
        // 优先级在 Fallback (HIGHEST) 之后，Template(NORMAL) 之前
        return HIGHEST + 50;
    }

    @Override
    public boolean supports(RenderContext context) {
        ErrorDef routeDef = context.getRouteDef();
        return routeDef != null && StrUtil.isNotEmpty(routeDef.getI18nKey());
    }

    @Override
    public void render(RenderContext context) {
        String i18nKey = context.getRouteDef().getI18nKey();

        // 此处为扩展预留：
        // 实际应用中，可结合 Spring MessageSource、或者配置中心
        // String i18nTemplate = messageSource.getMessage(i18nKey, null, LocaleContextHolder.getLocale());

        // 在本极简架构验证场景下，我们假设存在一个 mock 的寻找逻辑：
        // 如果能够找到对应语种配置，则覆盖 defaultMsg；否则保持原形交由下游 Template 解析。
        String i18nTemplate = resolveI18nMessage(i18nKey);

        if (StrUtil.isNotEmpty(i18nTemplate)) {
            context.setFinalMessage(i18nTemplate);
        }
    }

    /**
     * 模拟寻找多语言配置
     */
    private String resolveI18nMessage(String i18nKey) {
        // mock logic for tests
        if ("order.invalid".equals(i18nKey)) {
            return "Order has been invalid";
        }
        return null;
    }
}
