package com.team4u.criterion.compiler.impl;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.text.AntPathMatcher;
import cn.hutool.log.Log;
import com.team4u.criterion.MatchPredicate;
import com.team4u.criterion.compiler.AbstractCriterionCompiler;
import com.team4u.criterion.model.CriterionVisitor;
import com.team4u.criterion.model.WildcardCriterion;

/**
 * 通配符匹配规则编译器
 */
public class WildcardCriterionCompiler extends AbstractCriterionCompiler<WildcardCriterion> {

    private final Log log = Log.get();
    private final AntPathMatcher matcher = new AntPathMatcher();

    @Override
    public MatchPredicate compile(WildcardCriterion criterion,
            CriterionVisitor<MatchPredicate> visitor) {
        String pattern = criterion.getPattern();

        return context -> {
            try {
                if (pattern == null && context.getActual() == null) {
                    return true;
                }

                if (pattern == null) {
                    log.warn("WildcardCriterionCompiler|match|fail|msg=pattern is null");
                    return false;
                }

                return matcher.match(pattern, Convert.toStr(context.getActual()));
            } catch (Exception e) {
                return handleException(e, context);
            }
        };
    }
}
