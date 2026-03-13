package com.team4u.framework.criterion.compiler.impl;

import com.team4u.framework.base.util.ObjectUtil;
import com.team4u.framework.criterion.MatchPredicate;
import com.team4u.framework.criterion.compiler.AbstractCriterionCompiler;
import com.team4u.framework.criterion.model.CriterionVisitor;
import com.team4u.framework.criterion.model.NullCriterion;

/**
 * 空值/存在性匹配规则编译器
 */
public class NullCriterionCompiler extends AbstractCriterionCompiler<NullCriterion> {

    @Override
    public MatchPredicate compile(NullCriterion criterion,
                                  CriterionVisitor<MatchPredicate> visitor) {
        NullCriterion.Type type = criterion.getType();

        switch (type) {
            case NULL:
                return safe(context -> context.getActual() == null);
            case EMPTY:
                return safe(context -> ObjectUtil.isEmpty(context.getActual()));
            case NOT_EMPTY:
                return safe(context -> ObjectUtil.isNotEmpty(context.getActual()));
            default:
                return context -> false;
        }
    }
}
