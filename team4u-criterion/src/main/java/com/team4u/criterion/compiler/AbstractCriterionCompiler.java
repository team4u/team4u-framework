package com.team4u.criterion.compiler;

import cn.hutool.core.util.TypeUtil;
import cn.hutool.log.Log;
import com.team4u.criterion.MatchContext;
import com.team4u.criterion.MatchPredicate;
import com.team4u.criterion.model.Criterion;
import com.team4u.criterion.model.CriterionEvaluationException;

import java.util.function.Function;

/**
 * 抽象标准编译器
 * <p>
 * 自动处理泛型类型匹配逻辑
 *
 * @author jay.wu
 */
public abstract class AbstractCriterionCompiler<C extends Criterion> implements CriterionCompiler<C> {

    private final Class<C> criterionType;

    @SuppressWarnings("unchecked")
    public AbstractCriterionCompiler() {
        this.criterionType = (Class<C>) TypeUtil.getTypeArgument(getClass());
    }

    @Override
    public Class<? extends Criterion> key() {
        return criterionType;
    }

    /**
     * 处理评估异常
     */
    protected boolean handleException(Exception e, MatchContext context) {
        // 严格模式下抛出求值异常
        if (context.isStrictMode()) {
            throw new CriterionEvaluationException(e.getMessage(), e);
        }

        Log.get().error("{}|evaluate|fail|msg={}", this.getClass().getSimpleName(), e.getMessage(), e);
        return false;
    }

    /**
     * 高阶函数：支持转换器的 Null 值安全检查
     * <p>
     * 自动处理：1. 原始值 null 检查 2. 转换后值 null 检查 3. 异常包裹
     *
     * @param transformer 转换逻辑
     * @param predicate   核心匹配逻辑（入参包含转换后的非空目标对象）
     * @param <T>         转换后的目标类型
     * @return 增强后的匹配逻辑
     */
    protected <T> MatchPredicate safeNotNull(Function<Object, T> transformer,
            MatchPredicateWithTarget<T> predicate) {
        return context -> {
            try {
                Object rawActual = context.getActual();
                if (rawActual == null) {
                    return false;
                }
                T actual = transformer.apply(rawActual);
                if (actual == null) {
                    return false;
                }
                return predicate.test(context, actual);
            } catch (Exception e) {
                return handleException(e, context);
            }
        };
    }

    /**
     * 高阶函数：统一处理 Null 值短路逻辑与异常处理
     * <p>
     * 如果被测试值为 null，则直接返回 false。
     *
     * @param predicate 实际的核心匹配逻辑
     * @return 包裹后的匹配逻辑
     */
    protected MatchPredicate safeNotNull(MatchPredicate predicate) {
        return context -> {
            try {
                if (context.getActual() == null) {
                    return false;
                }
                return predicate.test(context);
            } catch (Exception e) {
                return handleException(e, context);
            }
        };
    }

    /**
     * 高阶函数：统一包裹异常处理逻辑
     *
     * @param predicate 实际的核心匹配逻辑
     * @return 捕获异常后的匹配逻辑
     */
    protected MatchPredicate safe(MatchPredicate predicate) {
        return context -> {
            try {
                return predicate.test(context);
            } catch (Exception e) {
                return handleException(e, context);
            }
        };
    }

    /**
     * 带转换后目标的匹配谓词接口
     */
    @FunctionalInterface
    protected interface MatchPredicateWithTarget<T> {
        boolean test(MatchContext context, T target);
    }
}
