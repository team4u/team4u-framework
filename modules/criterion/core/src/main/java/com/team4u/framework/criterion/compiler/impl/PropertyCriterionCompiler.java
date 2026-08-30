package com.team4u.framework.criterion.compiler.impl;

import com.team4u.framework.base.util.BeanUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.team4u.framework.criterion.MatchPredicate;
import com.team4u.framework.criterion.compiler.CriterionCompiler;
import com.team4u.framework.criterion.model.Criterion;
import com.team4u.framework.criterion.model.CriterionEvaluationException;
import com.team4u.framework.criterion.model.CriterionVisitor;
import com.team4u.framework.criterion.model.PropertyCriterion;

/**
 * 属性提取规则编译器
 */
public class PropertyCriterionCompiler implements CriterionCompiler<PropertyCriterion> {

    private final Logger log = LoggerFactory.getLogger(PropertyCriterionCompiler.class);

    @Override
    public MatchPredicate compile(PropertyCriterion criterion, CriterionVisitor<MatchPredicate> visitor) {
        String propertyName = criterion.getName();

        // 预编译子规则
        MatchPredicate childFunc = criterion.getCriterion().accept(visitor);

        // 返回闭包
        return context -> {
            try {
                Object actual = context.getActual();

                if (actual == null) {
                    return false;
                }

                Object propertyValue = BeanUtil.getProperty(actual, propertyName);

                // 切换上下文并执行子规则
                return childFunc.test(context.withActual(propertyValue));
            } catch (Exception e) {
                // 严格模式下透传属性访问或子规则求值异常
                if (context.isStrictMode()) {
                    throw new CriterionEvaluationException(e.getMessage(), e);
                }
                log.error("PropertyCriterionCompiler|match|fail|msg={}|property={}|expression={}",
                        e.getMessage(), propertyName, criterion.getExpression(), e);
                return false;
            }
        };
    }

    @Override
    public Class<? extends Criterion> key() {
        return PropertyCriterion.class;
    }
}
