package com.team4u.framework.singleflight.policy;

import com.team4u.framework.base.util.TextTemplate;

import java.util.Map;
import java.util.Set;

/**
 * key 解析器：基于 {@link TextTemplate} 在参数名 Map 上渲染规则 key 模板。
 * <p>
 * 任一模板变量缺失或值为 null 时整体渲染为 {@code null}，交由引擎按 onInvalidKey
 * 策略处置——这样避免了字面量 {@code ${name}} 被静默当作协调 key，
 * 让不同调用因变量缺失而意外共享同一个执行窗口。
 * </p>
 *
 * @author jay.wu
 */
public class KeyResolver {

    private final TextTemplate template;

    public KeyResolver(String template) {
        this.template = new TextTemplate(template);
    }

    /**
     * 渲染业务 key：变量齐全且渲染结果非空白才返回，否则返回 null。
     */
    public String render(Map<String, Object> arguments) {
        for (String name : variableNames()) {
            if (arguments == null || arguments.get(name) == null) {
                return null;
            }
        }
        String rendered = template.render(name -> {
            Object value = arguments == null ? null : arguments.get(name);
            return value == null ? null : String.valueOf(value);
        });
        return rendered == null || rendered.trim().isEmpty() ? null : rendered;
    }

    /**
     * 模板引用的全部变量名，供引擎做变量可解析性预检。
     */
    public Set<String> variableNames() {
        return template.getVariableNames();
    }
}
