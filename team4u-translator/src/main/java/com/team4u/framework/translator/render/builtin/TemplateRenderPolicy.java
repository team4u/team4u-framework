package com.team4u.framework.translator.render.builtin;

import com.team4u.framework.base.cache.Cache;
import com.team4u.framework.base.cache.CacheUtil;
import com.team4u.framework.base.util.StringUtil;
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

    /**
     * 模板解析缓存，基于 LRU 策略提升频繁访问相同模板时的性能
     */
    private static final Cache<String, TextTemplate> TEMPLATE_CACHE = CacheUtil.newLRUCache(256);

    @Override
    public boolean supports(RenderContext context) {
        // 如果最终消息中包含我们关注的模板标识（例如带有 ${ ），才启动模板渲染
        return StringUtil.isNotEmpty(context.getFinalMessage()) && context.getFinalMessage().contains("${");
    }

    @Override
    public void render(RenderContext context) {
        String templateStr = context.getFinalMessage();

        // 预获取或解析模板
        TextTemplate template = getTemplate(templateStr);
        if (!template.isDynamic()) {
            return;
        }

        // 构建渲染变量 Map
        // 合并请求级的额外参数
        Map<String, Object> renderArgs = new HashMap<>(context.getArgs());
        // 默认注入原始请求上下文（如果外部无同名变量覆盖）
        renderArgs.putIfAbsent("rawCode", context.getSource().getCode());
        renderArgs.putIfAbsent("rawMessage", context.getSource().getMessage());

        // 渲染文本并写回上下文
        String renderedStr = template.render(renderArgs);
        context.setFinalMessage(renderedStr);
    }

    /**
     * 获取指定模板字符串对应的 TextTemplate 对象
     * 优先从缓存获取，未命中则新建并写入缓存
     *
     * @param templateStr 模板字符串
     * @return TextTemplate 解析后模板实例
     */
    private TextTemplate getTemplate(String templateStr) {
        TextTemplate template = TEMPLATE_CACHE.get(templateStr);
        if (template != null) {
            return template;
        }
        TextTemplate created = new TextTemplate(templateStr);
        TEMPLATE_CACHE.put(templateStr, created);
        return created;
    }
}
