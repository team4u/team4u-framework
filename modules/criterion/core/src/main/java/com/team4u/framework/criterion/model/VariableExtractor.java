package com.team4u.framework.criterion.model;

import com.team4u.framework.criterion.model.value.Value;
import com.team4u.framework.criterion.model.value.VariableValue;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 变量提取器
 * <p>
 * 用于分析规则表达式中涉及的属性名和动态变量名
 *
 * @author jay.wu
 */
public class VariableExtractor implements CriterionVisitor<Set<String>> {

    private final Set<String> variables = new LinkedHashSet<>();

    /**
     * 提取表达式中的所有变量（属性名 + 动态变量）
     */
    public static Set<String> extract(Criterion criterion) {
        VariableExtractor extractor = new VariableExtractor();
        if (criterion != null) {
            criterion.accept(extractor);
        }
        return extractor.variables;
    }

    @Override
    public Set<String> visit(Criterion criterion) {
        if (criterion == null) {
            return variables;
        }

        // 1. 处理结构节点（逻辑组合）
        if (criterion instanceof LogicCriterion) {
            for (Criterion child : ((LogicCriterion) criterion).getChildren()) {
                child.accept(this);
            }
            return variables;
        }

        // 2. 处理属性节点（提取属性名，并继续遍历子节点）
        if (criterion instanceof PropertyCriterion) {
            PropertyCriterion pc = (PropertyCriterion) criterion;
            variables.add(pc.getName());
            return pc.getCriterion().accept(this);
        }

        // 3. 处理值节点（通过通用接口提取 Value）
        if (criterion instanceof ValueContainer) {
            for (Value<?> value : ((ValueContainer) criterion).values()) {
                addValue(value);
            }
        }

        return variables;
    }

    private void addValue(Value<?> value) {
        if (value instanceof VariableValue) {
            variables.add(((VariableValue<?>) value).getVariableName());
        }
    }
}