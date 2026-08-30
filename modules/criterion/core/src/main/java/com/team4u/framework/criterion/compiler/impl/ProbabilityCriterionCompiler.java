package com.team4u.framework.criterion.compiler.impl;

import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.team4u.framework.criterion.MatchPredicate;
import com.team4u.framework.criterion.compiler.AbstractCriterionCompiler;
import com.team4u.framework.criterion.compiler.CompiledValue;
import com.team4u.framework.criterion.compiler.ValueOptimizer;
import com.team4u.framework.criterion.model.CriterionVisitor;
import com.team4u.framework.criterion.model.ProbabilityCriterion;

/**
 * 概率匹配规则编译器
 */
public class ProbabilityCriterionCompiler extends AbstractCriterionCompiler<ProbabilityCriterion> {

    private final Logger log = LoggerFactory.getLogger(ProbabilityCriterionCompiler.class);

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

            return ThreadLocalRandom.current().nextDouble() <= threshold;
        });
    }
}
