package com.team4u.criterion.compiler.impl;

import cn.hutool.core.util.HashUtil;
import cn.hutool.log.Log;
import com.team4u.criterion.MatchContext;
import com.team4u.criterion.MatchPredicate;
import com.team4u.criterion.compiler.AbstractCriterionCompiler;
import com.team4u.criterion.compiler.CompiledValue;
import com.team4u.criterion.compiler.ValueOptimizer;
import com.team4u.criterion.model.CriterionVisitor;
import com.team4u.criterion.model.HashProbabilityCriterion;

/**
 * Hash 概率分流规则编译器
 */
public class HashProbabilityCriterionCompiler extends AbstractCriterionCompiler<HashProbabilityCriterion> {

    private final Log log = Log.get();

    @Override
    public MatchPredicate compile(HashProbabilityCriterion criterion,
            CriterionVisitor<MatchPredicate> visitor) {
        // 使用优化器统一处理静态值和动态值的取值逻辑
        CompiledValue<Double> thresholdGetter = ValueOptimizer.optimize(
                criterion.getThreshold(),
                num -> num == null ? null : num.doubleValue());

        return safe(context -> {
            Double threshold = thresholdGetter.get(context);
            if (threshold == null) {
                log.error("HashProbabilityCriterionCompiler|match|fail|msg=threshold is null");
                return false;
            }

            return doMatch(context, threshold);
        });
    }

    private boolean doMatch(MatchContext context, double threshold) {
        Object actual = context.getActual();
        if (actual == null) {
            return false;
        }

        // 尝试获取盐值（开关名或规则标识），用于保证不同开关的分流正交性
        String salt = context.getAttribute("salt", "");

        // 1. 计算输入值的 Hash (使用 MurmurHash 算法)
        // 拼接 salt 和 actual，降低字符串转换开销的同时保证雪崩效应
        byte[] data = (salt + actual).getBytes();
        long hash = HashUtil.murmur64(data) & Long.MAX_VALUE;

        // 2. 将 Hash 值映射到 0.0 - 1.0 范围
        double scale = (hash % 10000) / 10000.0;

        // 3. 判断是否落在概率区间内
        return scale < threshold;
    }
}
