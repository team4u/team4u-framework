package com.team4u.framework.singleflight.policy;

import com.team4u.framework.base.util.TextTemplate;

import java.util.Map;
import java.util.Set;

/**
 * Key resolver using {@link TextTemplate} over the execution argument map.
 * <p>
 * An unresolved or null variable renders to {@code null}; the engine then applies
 * its configured invalid-key policy. This prevents a literal {@code ${name}}
 * silently becoming a coordination key.
 * </p>
 *
 * @author jay.wu
 */
public class KeyResolver {

    private final TextTemplate template;

    public KeyResolver(String template) {
        this.template = new TextTemplate(template);
    }

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

    public Set<String> variableNames() {
        return template.getVariableNames();
    }
}
