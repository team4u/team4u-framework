package com.team4u.framework.singleflight.core;

import com.team4u.framework.criterion.model.Criterion;
import com.team4u.framework.criterion.model.PropertyCriterion;
import com.team4u.framework.criterion.model.ValueContainer;
import com.team4u.framework.criterion.model.value.Value;
import com.team4u.framework.criterion.model.value.VariableValue;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 从 Criterion 表达式 AST 中提取属性名与变量名。
 * <p>
 * 引擎用它做 skipWhen 变量的可解析性预检：表达式引用的每个属性 / 变量都必须
 * 出现在调用方提供的参数名集合里，配置笔误在执行期第一时间暴露而不是静默不命中。
 * </p>
 *
 * @author jay.wu
 */
public final class CriterionNames {

    private CriterionNames() {
    }

    /**
     * 深度优先遍历条件树，收集全部属性名与变量名（保持出现顺序，去重）。
     */
    public static Set<String> extract(Criterion criterion) {
        Set<String> names = new LinkedHashSet<>();
        collect(criterion, names);
        return names;
    }

    private static void collect(Criterion criterion, Set<String> names) {
        if (criterion == null) {
            return;
        }
        // 逻辑组合节点：递归收集全部子条件
        if (criterion instanceof com.team4u.framework.criterion.model.LogicCriterion) {
            for (Criterion child : ((com.team4u.framework.criterion.model.LogicCriterion) criterion).getChildren()) {
                collect(child, names);
            }
            return;
        }
        // 属性节点：先记属性名，再深入其值条件继续收集
        if (criterion instanceof PropertyCriterion) {
            names.add(((PropertyCriterion) criterion).getName());
            collect(((PropertyCriterion) criterion).getCriterion(), names);
            return;
        }
        // 值容器节点：收集其中变量值引用的变量名
        if (criterion instanceof ValueContainer) {
            for (Value<?> value : ((ValueContainer) criterion).values()) {
                if (value instanceof VariableValue) {
                    names.add(((VariableValue<?>) value).getVariableName());
                }
            }
        }
    }
}
