package com.team4u.criterion.compiler.impl;

import com.team4u.criterion.MatchPredicate;
import com.team4u.criterion.compiler.AbstractCriterionCompiler;
import com.team4u.criterion.compiler.CompiledValue;
import com.team4u.criterion.compiler.ValueOptimizer;
import com.team4u.criterion.model.ContainsAnyCriterion;
import com.team4u.criterion.model.CriterionVisitor;
import com.team4u.criterion.util.CriterionCollectionUtil;

import java.util.Collection;
import java.util.Collections;
import java.util.Set;

/**
 * 集合交集检查编译器
 */
public class ContainsAnyCriterionCompiler
        extends AbstractCriterionCompiler<ContainsAnyCriterion> {

    @Override
    public MatchPredicate compile(
            ContainsAnyCriterion criterion,
            CriterionVisitor<MatchPredicate> visitor) {

        // 使用优化器统一处理静态值和动态值的集合构建逻辑
        CompiledValue<Set<Object>> expectedSetGetter = ValueOptimizer.optimizeToSet(criterion.getValues());

        return safeNotNull(context -> {
            Object actual = context.getActual();

            // 获取期望比对的集合（可能是预编译的静态集合，也可能是运行时动态构建的）
            Set<Object> expectedSet = expectedSetGetter.get(context);

            if (expectedSet.isEmpty()) {
                return false;
            }

            // 尝试将 actual 转换为 Collection 进行统一比较。
            Collection<?> actualColl = CriterionCollectionUtil.toCollection(actual);

            if (actualColl != null) {
                // 利用 Collections.disjoint 判断交集（取反即为有交集）。
                return !Collections.disjoint(actualColl, expectedSet);
            }

            // 兜底处理：如果不是集合结构或者无法匹配判断。
            return false;
        });
    }
}
