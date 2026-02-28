package com.team4u.framework.translator.render.builtin;

import cn.hutool.core.util.StrUtil;
import com.team4u.framework.base.util.TextTemplate;
import com.team4u.framework.translator.model.RenderContext;
import com.team4u.framework.translator.render.RenderPolicy;

import java.util.HashMap;
import java.util.Map;

/**
 * 内置：变量模板渲染器
 * <p>
 * 提取 context.getFinalMessage() 作为模板字符串，并执行变量替换。
 */
public class TemplateRenderPolicy implements RenderPolicy {

    @Override
    public boolean supports(RenderContext context) {
        // 如果 FinalMessage 中包含我们关注的模板标识（例如文本里带有 $），才启动模板渲染
        return StrUtil.isNotEmpty(context.getFinalMessage()) && context.getFinalMessage().contains("$");
    }

    @Override
    public void render(RenderContext context) {
        String templateStr = context.getFinalMessage();

        // 预解析模板
        TextTemplate template = new TextTemplate(templateStr);
        if (!template.isDynamic()) {
            return;
        }

        // 构建渲染变量 Map
        Map<String, Object> renderArgs = new HashMap<>();

        // 合并请求级的额外参数
        if (context.getArgs() != null) {
            renderArgs.putAll(context.getArgs());
        }

        // 默认注入原始请求上下文（如果外部无同名变量覆盖）
        renderArgs.putIfAbsent("rawCode", context.getSource().getCode());
        renderArgs.putIfAbsent("rawMessage", context.getSource().getMessage());

        // 渲染文本并写回上下文
        String renderedStr = template.render(renderArgs);
        context.setFinalMessage(renderedStr);
    }
}
