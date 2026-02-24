package com.team4u.criterion.compiler.impl;

import com.team4u.criterion.MatchPredicate;
import com.team4u.criterion.compiler.AbstractCriterionCompiler;
import com.team4u.criterion.model.CriterionVisitor;
import com.team4u.criterion.model.LogicCriterion;

/**
 * 逻辑组合规则编译器
 */
public class LogicCriterionCompiler extends AbstractCriterionCompiler<LogicCriterion> {

    @Override
    public MatchPredicate compile(LogicCriterion criterion, CriterionVisitor<MatchPredicate> visitor) {
        // 递归预编译所有子节点 (Compile-time)
        // 将 list 转换为数组，运行时遍历数组比 list 快一点点，且无迭代器对象生成
        MatchPredicate[] funcs = criterion.getChildren().stream()
                .map(visitor::visit)
                .toArray(MatchPredicate[]::new);

        // 返回闭包
        if (criterion.getOperator() == LogicCriterion.Operator.AND) {
            return safe(context -> {
                for (MatchPredicate func : funcs) {
                    if (!func.test(context)) {
                        return false; // 短路
                    }
                }
                return true;
            });
        } else {
            return context -> {
                for (MatchPredicate func : funcs) {
                    try {
                        if (func.test(context)) {
                            return true; // 短路
                        }
                    } catch (Exception e) {
                        handleException(e, context);
                        // OR 逻辑下，异常被忽略，继续评估下一个条件
                    }
                }
                return false;
            };
        }
    }
}
