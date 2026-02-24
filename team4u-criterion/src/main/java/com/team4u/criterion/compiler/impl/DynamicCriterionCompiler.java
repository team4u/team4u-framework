package com.team4u.criterion.compiler.impl;

import com.team4u.criterion.MatchPredicate;
import com.team4u.criterion.compiler.AbstractCriterionCompiler;
import com.team4u.criterion.model.CriterionVisitor;
import com.team4u.criterion.model.DynamicCriterion;
import com.team4u.criterion.model.value.Value;

import java.util.function.BiPredicate;

/**
 * 动态规则编译器
 */
public class DynamicCriterionCompiler extends AbstractCriterionCompiler<DynamicCriterion> {

    @Override
    public MatchPredicate compile(DynamicCriterion criterion,
                                  CriterionVisitor<MatchPredicate> visitor) {
        BiPredicate<Object, Object> logic = criterion.getLogic();
        Value<?> valueProvider = criterion.getValue();

        return safe(context -> logic.test(
                context.getActual(),
                valueProvider.get(context)));
    }
}
