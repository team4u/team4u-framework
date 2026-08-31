package com.team4u.framework.flow.criterion;

import com.team4u.framework.criterion.Criteria;
import com.team4u.framework.criterion.MatchContext;
import lombok.Getter;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 基于 {@link Criteria} 规则表达式的强类型条件谓词。
 *
 * <p>可在 Flow 的条件分支（如 {@code when}、{@code route}、{@code firstApplicable} 等）中无缝作为
 * {@link Predicate} 传入，利用动态表达式（如 {@code "amount >= 1000 and user.vip == true"}）进行分支判定。</p>
 *
 * @param <T> 输入上下文类型
 * @author jay.wu
 */
@Getter
public final class CriterionPredicate<T> implements Predicate<T> {

    private final String expression;
    private final Criteria criteria;
    private final Function<T, Object> targetExtractor;

    public CriterionPredicate(String expression, Criteria criteria, Function<T, Object> targetExtractor) {
        this.expression = Objects.requireNonNull(expression, "expression must not be null");
        this.criteria = criteria != null ? criteria : Criteria.global();
        this.targetExtractor = targetExtractor;
    }

    @Override
    public boolean test(T input) {
        Object target = targetExtractor != null ? targetExtractor.apply(input) : input;
        if (target == null) {
            return false;
        }
        if (target instanceof MatchContext) {
            return criteria.matches(expression, (MatchContext) target);
        }
        return criteria.matches(expression, target);
    }
}
