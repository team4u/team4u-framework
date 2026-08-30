package com.team4u.framework.criterion.compiler.impl;

import com.team4u.framework.criterion.model.Criterion;

import com.team4u.framework.criterion.MatchPredicate;
import com.team4u.framework.criterion.compiler.AbstractCriterionCompiler;
import com.team4u.framework.criterion.model.CriterionVisitor;
import com.team4u.framework.criterion.model.InCriterion;
import com.team4u.framework.criterion.model.value.FixedValue;
import com.team4u.framework.criterion.model.value.Value;
import com.team4u.framework.criterion.util.CriterionCollectionUtil;
import com.team4u.framework.criterion.util.FastNumberUtil;
import com.team4u.framework.criterion.util.ObjectCompareUtil;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * In 规则编译器
 */
public class InCriterionCompiler extends AbstractCriterionCompiler<InCriterion> {

    @Override
    public MatchPredicate compile(InCriterion criterion, CriterionVisitor<MatchPredicate> visitor) {
        // 1. 尝试进行静态优化
        if (isAllFixedValues(criterion)) {
            return compileStatic(criterion);
        }

        // 2. 存在动态变量，降级为默认的运行时计算逻辑
        return safeNotNull(context -> {
            Set<Object> dynamicSet = new HashSet<>();
            for (Value<?> provider : criterion.getValues()) {
                resolveAndAdd(dynamicSet, provider.get(context));
            }
            return checkWithOptimizedShortCircuit(dynamicSet, context.getActual(), criterion.isNot());
        });
    }

    /**
     * 编译静态集合 (快速路径)
     */
    private MatchPredicate compileStatic(InCriterion criterion) {
        Set<Long> longSet = new HashSet<>();
        Set<Double> doubleSet = new HashSet<>();
        Set<String> stringSet = new HashSet<>();
        Set<Object> otherSet = new HashSet<>();

        for (Value<?> provider : criterion.getValues()) {
            Object v = provider.get(null);
            Collection<?> coll = CriterionCollectionUtil.toCollection(v);
            if (coll != null) {
                for (Object item : coll) {
                    categorizeValue(item, longSet, doubleSet, stringSet, otherSet);
                }
            }
        }

        boolean isNot = criterion.isNot();

        return context -> {
            Object actual = context.getActual();
            if (actual == null) {
                return isNot != otherSet.contains(null);
            }

            // 1. 优先尝试作为数字查找
            Number actualNum = FastNumberUtil.toNumber(actual);
            if (actualNum != null && checkNumberInSets(actualNum, doubleSet, longSet)) {
                return !isNot;
            }

            // 2. 尝试字符串精准查找
            if (stringSet.contains(actual.toString())) {
                return !isNot;
            }

            // 3. 兜底：原始对象引用/equals
            boolean found = otherSet.contains(actual);
            return isNot != found;
        };
    }

    private boolean checkNumberInSets(Number actualNum, Set<Double> doubleSet, Set<Long> longSet) {
        if (FastNumberUtil.isFloatingPoint(actualNum)) {
            if (doubleSet.contains(actualNum.doubleValue())) {
                return true;
            }
            // 如果在浮点集合没找到，尝试在整数集合查找对应的 double 值（如 1.0 == 1）
            return actualNum.doubleValue() == Math.floor(actualNum.doubleValue()) &&
                    longSet.contains(actualNum.longValue());
        }

        if (longSet.contains(actualNum.longValue())) {
            return true;
        }
        // 如果在整数集合没找到，尝试在浮点集合查找
        return doubleSet.contains(actualNum.doubleValue());
    }

    private void categorizeValue(Object v, Set<Long> longSet, Set<Double> doubleSet, Set<String> stringSet,
            Set<Object> otherSet) {
        if (v == null) {
            otherSet.add(null);
            return;
        }
        Number n = FastNumberUtil.toNumber(v);
        if (n != null) {
            if (FastNumberUtil.isFloatingPoint(n)) {
                doubleSet.add(n.doubleValue());
            } else {
                longSet.add(n.longValue());
            }
        }
        stringSet.add(v.toString());
        otherSet.add(v);
    }

    /**
     * 带短路优化的检查逻辑（动态分支）
     */
    private boolean checkWithOptimizedShortCircuit(Set<Object> values, Object actual, boolean isNot) {
        boolean found = values.contains(actual);

        if (!found) {
            for (Object expected : values) {
                if (ObjectCompareUtil.looseEquals(actual, expected)) {
                    found = true;
                    break;
                }
            }
        }
        return isNot != found;
    }

    private void resolveAndAdd(Set<Object> set, Object value) {
        Collection<?> coll = CriterionCollectionUtil.toCollection(value);
        if (coll != null) {
            set.addAll(coll);
        }
    }

    private boolean isAllFixedValues(InCriterion criterion) {
        for (Value<?> value : criterion.getValues()) {
            if (!(value instanceof FixedValue)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public Class<? extends Criterion> key() {
        return InCriterion.class;
    }
}
