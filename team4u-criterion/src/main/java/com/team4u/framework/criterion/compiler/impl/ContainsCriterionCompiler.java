package com.team4u.framework.criterion.compiler.impl;

import com.team4u.framework.criterion.MatchPredicate;
import com.team4u.framework.criterion.compiler.AbstractCriterionCompiler;
import com.team4u.framework.criterion.model.ContainsCriterion;
import com.team4u.framework.criterion.model.CriterionVisitor;
import com.team4u.framework.criterion.model.value.Value;
import com.team4u.framework.criterion.util.CriterionCollectionUtil;
import com.team4u.framework.criterion.util.ObjectCompareUtil;

import java.util.Collection;

/**
 * 集合包含元素编译器
 */
public class ContainsCriterionCompiler extends AbstractCriterionCompiler<ContainsCriterion> {

    @Override
    public MatchPredicate compile(ContainsCriterion criterion,
                                  CriterionVisitor<MatchPredicate> visitor) {
        Value<?> valueProvider = criterion.getValueProvider();

        return safeNotNull(context -> {
            Object actual = context.getActual();

            // 运行时解析期望值
            Object expected = valueProvider.get(context);

            // 处理字符串包含逻辑
            if (actual instanceof String && expected instanceof String) {
                return ((String) actual).contains((String) expected);
            }

            // 统一转换为集合处理
            Collection<?> coll = CriterionCollectionUtil.toCollection(actual);
            if (coll != null) {
                for (Object item : coll) {
                    if (ObjectCompareUtil.looseEquals(item, expected)) {
                        return true;
                    }
                }
            }

            return false;
        });
    }
}
