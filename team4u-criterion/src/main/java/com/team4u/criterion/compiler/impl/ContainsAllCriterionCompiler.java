package com.team4u.criterion.compiler.impl;

import com.team4u.criterion.MatchPredicate;
import com.team4u.criterion.compiler.AbstractCriterionCompiler;
import com.team4u.criterion.compiler.CompiledValue;
import com.team4u.criterion.compiler.ValueOptimizer;
import com.team4u.criterion.model.ContainsAllCriterion;
import com.team4u.criterion.model.CriterionVisitor;
import com.team4u.criterion.util.CriterionCollectionUtil;

import java.util.Collection;
import java.util.Set;

/**
 * 集合全集包含编译器
 */
public class ContainsAllCriterionCompiler
        extends AbstractCriterionCompiler<ContainsAllCriterion> {

    @Override
    public MatchPredicate compile(
            ContainsAllCriterion criterion,
            CriterionVisitor<MatchPredicate> visitor) {

        // 使用优化器统一处理静态值和动态值的集合构建逻辑
        CompiledValue<Set<Object>> expectedSetGetter = ValueOptimizer.optimizeToSet(criterion.getValues());

        return safeNotNull(context -> {
            Object actual = context.getActual();

            // 获取期望包含的所有元素集合
            Set<Object> expectedSet = expectedSetGetter.get(context);

            // 根据集合论，任何集合都包含空集
            if (expectedSet.isEmpty()) {
                return true;
            }

            // 统一将实际值转换为 Collection 处理（兼容 List, Set, Array 等）
            Collection<?> actualColl = CriterionCollectionUtil.toCollection(actual);

            if (actualColl != null) {
                // 判断实际集合是否包含期望集合中的所有元素
                return actualColl.containsAll(expectedSet);
            }

            return false;
        });
    }
}
