package com.team4u.framework.singleflight.core;

import com.team4u.framework.criterion.model.Criterion;
import com.team4u.framework.criterion.model.PropertyCriterion;
import com.team4u.framework.criterion.model.ValueContainer;
import com.team4u.framework.criterion.model.value.Value;
import com.team4u.framework.criterion.model.value.VariableValue;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Extracts property and variable names from a criterion AST.
 *
 * @author jay.wu
 */
public final class CriterionNames {

    private CriterionNames() {
    }

    public static Set<String> extract(Criterion criterion) {
        Set<String> names = new LinkedHashSet<>();
        collect(criterion, names);
        return names;
    }

    private static void collect(Criterion criterion, Set<String> names) {
        if (criterion == null) {
            return;
        }
        if (criterion instanceof com.team4u.framework.criterion.model.LogicCriterion) {
            for (Criterion child : ((com.team4u.framework.criterion.model.LogicCriterion) criterion).getChildren()) {
                collect(child, names);
            }
            return;
        }
        if (criterion instanceof PropertyCriterion) {
            names.add(((PropertyCriterion) criterion).getName());
            collect(((PropertyCriterion) criterion).getCriterion(), names);
            return;
        }
        if (criterion instanceof ValueContainer) {
            for (Value<?> value : ((ValueContainer) criterion).values()) {
                if (value instanceof VariableValue) {
                    names.add(((VariableValue<?>) value).getVariableName());
                }
            }
        }
    }
}
