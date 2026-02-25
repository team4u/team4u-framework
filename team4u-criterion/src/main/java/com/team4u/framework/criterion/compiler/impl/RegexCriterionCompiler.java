package com.team4u.framework.criterion.compiler.impl;

import cn.hutool.core.convert.Convert;
import com.team4u.framework.criterion.MatchPredicate;
import com.team4u.framework.criterion.compiler.AbstractCriterionCompiler;
import com.team4u.framework.criterion.model.CriterionVisitor;
import com.team4u.framework.criterion.model.RegexCriterion;

import java.util.regex.Pattern;

/**
 * 正则匹配规则编译器
 */
public class RegexCriterionCompiler extends AbstractCriterionCompiler<RegexCriterion> {

    @Override
    public MatchPredicate compile(RegexCriterion criterion,
                                  CriterionVisitor<MatchPredicate> visitor) {
        Pattern pattern = criterion.getPattern();

        if (pattern == null) {
            return context -> safe(ctx -> ctx.getActual() == null).test(context);
        }

        return safe(context -> {
            String actual = Convert.toStr(context.getActual(), "");
            return pattern.matcher(actual).matches();
        });
    }
}
