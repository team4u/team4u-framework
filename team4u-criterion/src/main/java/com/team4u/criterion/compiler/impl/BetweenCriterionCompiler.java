package com.team4u.criterion.compiler.impl;

import com.team4u.criterion.model.Criterion;

import com.team4u.criterion.MatchPredicate;
import com.team4u.criterion.compiler.AbstractCriterionCompiler;
import com.team4u.criterion.compiler.CompiledValue;
import com.team4u.criterion.compiler.ValueOptimizer;
import com.team4u.criterion.model.BetweenCriterion;
import com.team4u.criterion.model.CriterionVisitor;
import com.team4u.criterion.util.FastNumberUtil;

import java.util.function.Function;

/**
 * Between 规则编译器
 */
public class BetweenCriterionCompiler extends AbstractCriterionCompiler<BetweenCriterion> {

    @Override
    public MatchPredicate compile(BetweenCriterion criterion,
            CriterionVisitor<MatchPredicate> visitor) {
        Function<Object, Comparable<?>> typeConverter = criterion.getTypeConverter();
        boolean includeLower = criterion.isIncludeLower();
        boolean includeUpper = criterion.isIncludeUpper();

        // 使用优化器分别优化下界和上界
        CompiledValue<Comparable<?>> lowerGetter = ValueOptimizer.optimizeRaw(
                criterion.getLowerProvider(),
                typeConverter);
        CompiledValue<Comparable<?>> upperGetter = ValueOptimizer.optimizeRaw(
                criterion.getUpperProvider(),
                typeConverter);

        return safeNotNull(typeConverter, (context, actual) -> {
            // 获取边界值
            Comparable<?> lower = lowerGetter.get(context);
            Comparable<?> upper = upperGetter.get(context);

            if (lower == null || upper == null) {
                return false;
            }

            // 比较下界
            boolean lowerMatch = includeLower ? compare(actual, lower) >= 0 : compare(actual, lower) > 0;
            if (!lowerMatch) {
                return false;
            }

            // 比较上界
            return includeUpper ? compare(actual, upper) <= 0 : compare(actual, upper) < 0;
        });
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private int compare(Comparable actual, Comparable expected) {
        if (actual instanceof Number && expected instanceof Number) {
            return FastNumberUtil.compare((Number) actual, (Number) expected);
        }
        return actual.compareTo(expected);
    }

    @Override
    public Class<? extends Criterion> key() {
        return BetweenCriterion.class;
    }
}
