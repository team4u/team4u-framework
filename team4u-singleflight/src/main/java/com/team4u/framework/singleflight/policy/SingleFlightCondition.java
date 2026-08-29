package com.team4u.framework.singleflight.policy;

import com.team4u.framework.criterion.Criteria;
import com.team4u.framework.criterion.MatchContext;
import com.team4u.framework.criterion.model.VariableExtractor;
import lombok.EqualsAndHashCode;

import java.util.Set;

/**
 * 编译后的条件谓词（skipWhen / cacheWhen 共用）。
 * <p>
 * 语法编译发生在规则加载期，表达式错误在旧规则被替换前即暴露；
 * 便捷构造 {@link #compile(String)} 先经全局 Criteria 校验语法再持有表达式。
 * 实例按表达式文本判等，便于规则比较与缓存。
 * </p>
 *
 * @author jay.wu
 */
@EqualsAndHashCode(of = "expression")
public final class SingleFlightCondition {

    private final String expression;

    private SingleFlightCondition(String expression) {
        this.expression = expression;
    }

    /**
     * 编译条件表达式（加载期即校验语法，失败抛 Criterion 异常）。
     */
    public static SingleFlightCondition compile(String expression) {
        Criteria.global().compileExpression(expression);
        return new SingleFlightCondition(expression);
    }

    /**
     * 以对象为匹配目标执行匹配。
     */
    public boolean matches(Object actual) {
        return Criteria.global().matches(expression, actual);
    }

    /**
     * 以匹配上下文（对象 + 属性）执行匹配。
     */
    public boolean matches(MatchContext context) {
        return Criteria.global().matches(expression, context);
    }

    /**
     * 提取表达式引用的全部变量名，供变量可解析性预检。
     */
    public Set<String> variableNames() {
        return VariableExtractor.extract(Criteria.global().parse(expression));
    }

    public String expression() {
        return expression;
    }
}
