package com.team4u.framework.criterion.compiler.impl;

import com.team4u.framework.base.convert.ConvertUtil;
import com.team4u.framework.base.pattern.PathPatternMatcher;
import com.team4u.framework.criterion.MatchPredicate;
import com.team4u.framework.criterion.compiler.AbstractCriterionCompiler;
import com.team4u.framework.criterion.model.CriterionVisitor;
import com.team4u.framework.criterion.model.WildcardCriterion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 通配符匹配规则编译器
 */
public class WildcardCriterionCompiler extends AbstractCriterionCompiler<WildcardCriterion> {

    private final Logger log = LoggerFactory.getLogger(WildcardCriterionCompiler.class);

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

                return PathPatternMatcher.match(pattern, ConvertUtil.toStr(context.getActual()));
            } catch (Exception e) {
                return handleException(e, context);
            }
        };
    }
}
