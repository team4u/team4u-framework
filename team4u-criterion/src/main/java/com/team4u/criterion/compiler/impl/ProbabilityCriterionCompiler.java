package com.team4u.criterion.compiler.impl;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.log.Log;
import com.team4u.criterion.MatchPredicate;
import com.team4u.criterion.compiler.AbstractCriterionCompiler;
import com.team4u.criterion.compiler.CompiledValue;
import com.team4u.criterion.compiler.ValueOptimizer;
import com.team4u.criterion.model.CriterionVisitor;
import com.team4u.criterion.model.ProbabilityCriterion;

/**
 * 概率匹配规则编译器
 */
public class ProbabilityCriterionCompiler extends AbstractCriterionCompiler<ProbabilityCriterion> {

    private final Log log = Log.get();

    @Override
    public MatchPredicate compile(ProbabilityCriterion criterion,
            CriterionVisitor<MatchPredicate> visitor) {
        // 使用优化器统一处理静态值和动态值的取值逻辑
        CompiledValue<Double> thresholdGetter = ValueOptimizer.optimize(
                criterion.getThreshold(),
                num -> num == null ? null : num.doubleValue());

        return safe(context -> {
            Double threshold = thresholdGetter.get(context);
            if (threshold == null) {
                log.error("ProbabilityCriterionCompiler|match|fail|msg=threshold is null");
                return false;
            }

            return RandomUtil.randomDouble(0, 1) <= threshold;
        });
    }
}
