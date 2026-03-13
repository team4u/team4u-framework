package com.team4u.framework.criterion.compiler.impl;

import com.team4u.framework.base.convert.ConvertUtil;
import com.team4u.framework.criterion.MatchPredicate;
import com.team4u.framework.criterion.compiler.AbstractCriterionCompiler;
import com.team4u.framework.criterion.model.CriterionVisitor;
import com.team4u.framework.criterion.model.WildcardCriterion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.AntPathMatcher;

/**
 * 通配符匹配规则编译器
 */
public class WildcardCriterionCompiler extends AbstractCriterionCompiler<WildcardCriterion> {

    private final Logger log = LoggerFactory.getLogger(WildcardCriterionCompiler.class);
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

                return matcher.match(pattern, ConvertUtil.toStr(context.getActual()));
            } catch (Exception e) {
                return handleException(e, context);
            }
        };
    }
}
