package com.team4u.criterion.compiler.impl;

import com.team4u.criterion.model.Criterion;

import com.team4u.criterion.MatchPredicate;
import com.team4u.criterion.compiler.AbstractCriterionCompiler;
import com.team4u.criterion.compiler.CompiledValue;
import com.team4u.criterion.compiler.ValueOptimizer;
import com.team4u.criterion.model.ComparableCriterion;
import com.team4u.criterion.model.CompareOperators;
import com.team4u.criterion.model.CriterionVisitor;
import com.team4u.criterion.util.FastNumberUtil;

import java.util.function.Function;
import java.util.function.IntPredicate;

/**
 * Comparable 比较编译器
 */
public class ComparableCriterionCompiler extends AbstractCriterionCompiler<ComparableCriterion> {

    @Override
    public MatchPredicate compile(ComparableCriterion criterion, CriterionVisitor<MatchPredicate> visitor) {
        // 编译期确定比较逻辑
        IntPredicate logic = CompareOperators.get(criterion.getOperator());
        if (logic == null) {
            return MatchPredicate.FALSE;
        }

        Function<Object, Comparable<?>> converter = criterion.getTypeConverter();

        // 使用优化器统一处理静态值和动态值的取值逻辑
        CompiledValue<Comparable<?>> expectedGetter = ValueOptimizer.optimize(
                criterion.getExpectedValueProvider(),
                converter::apply);

        return safeNotNull(converter, (ctx, actual) -> {
            Comparable<?> expected = expectedGetter.get(ctx);
            return expected != null && match(logic, actual, expected);
        });
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private boolean match(IntPredicate logic, Comparable actual, Comparable expected) {
        if (actual instanceof Number && expected instanceof Number) {
            return logic.test(FastNumberUtil.compare((Number) actual, (Number) expected));
        }
        return logic.test(actual.compareTo(expected));
    }

    @Override
    public Class<? extends Criterion> key() {
        return ComparableCriterion.class;
    }
}
