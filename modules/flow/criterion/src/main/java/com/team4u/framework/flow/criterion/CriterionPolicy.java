package com.team4u.framework.flow.criterion;

import com.team4u.framework.criterion.Criteria;
import com.team4u.framework.criterion.MatchContext;
import com.team4u.framework.flow.api.Gate;
import com.team4u.framework.flow.api.Policy;
import com.team4u.framework.flow.api.PolicyContext;
import com.team4u.framework.flow.model.Failure;
import com.team4u.framework.flow.model.Reason;
import lombok.Builder;
import lombok.Getter;

import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * 基于 {@link Criteria} 表达式的无状态前置门控策略。
 *
 * <p>用于在流程节点前置切面中进行准入校验、风控拦截、动态开关与黑白名单判定。
 * 支持三种核心校验模式：
 * <ul>
 *   <li>{@link Mode#PERMIT_IF}：匹配表达式则放行，不匹配则拦截（拒绝或失败）；</li>
 *   <li>{@link Mode#REJECT_IF}：匹配表达式则以 {@link Reason} 业务拒绝短路，不匹配则放行；</li>
 *   <li>{@link Mode#FAIL_IF}：匹配表达式则以 {@link Failure} 系统故障抛出，不匹配则放行。</li>
 * </ul>
 * </p>
 *
 * @param <K> 策略路由键类型
 * @author jay.wu
 */
@Getter
public final class CriterionPolicy<K> implements Policy<K> {

    /**
     * 门控策略模式
     */
    public enum Mode {
        /** 匹配表达式时放行；不匹配时根据 action 进行拦截。 */
        PERMIT_IF,
        /** 匹配表达式时业务拒绝；不匹配时放行。 */
        REJECT_IF,
        /** 匹配表达式时系统故障失败；不匹配时放行。 */
        FAIL_IF
    }

    private final String expression;
    private final Criteria criteria;
    private final Mode mode;
    private final CriterionAction action;
    private final Function<K, Object> targetExtractor;
    private final BiFunction<PolicyContext, K, Reason> reasonFactory;
    private final BiFunction<PolicyContext, K, Failure> failureFactory;

    @Builder
    public CriterionPolicy(String expression,
                           Criteria criteria,
                           Mode mode,
                           CriterionAction action,
                           Function<K, Object> targetExtractor,
                           BiFunction<PolicyContext, K, Reason> reasonFactory,
                           BiFunction<PolicyContext, K, Failure> failureFactory) {
        this.expression = Objects.requireNonNull(expression, "expression must not be null");
        this.criteria = criteria != null ? criteria : Criteria.global();
        this.mode = mode != null ? mode : Mode.PERMIT_IF;
        this.action = action != null ? action : CriterionAction.REJECT;
        this.targetExtractor = targetExtractor;
        this.reasonFactory = reasonFactory != null ? reasonFactory :
                (ctx, key) -> Reason.of("CRITERION_REJECTED", "Rule rejected by expression: " + expression);
        this.failureFactory = failureFactory != null ? failureFactory :
                (ctx, key) -> Failure.of("CRITERION_FAILED", "Rule failed by expression: " + expression);
    }

    @Override
    public Gate before(PolicyContext context, K key) {
        Object target = targetExtractor != null ? targetExtractor.apply(key) : key;
        boolean matched = evaluate(target);

        switch (mode) {
            case PERMIT_IF:
                if (matched) {
                    return Gate.proceed();
                }
                return action == CriterionAction.FAIL
                        ? Gate.fail(failureFactory.apply(context, key))
                        : Gate.reject(reasonFactory.apply(context, key));

            case REJECT_IF:
                if (matched) {
                    return Gate.reject(reasonFactory.apply(context, key));
                }
                return Gate.proceed();

            case FAIL_IF:
                if (matched) {
                    return Gate.fail(failureFactory.apply(context, key));
                }
                return Gate.proceed();

            default:
                throw new IllegalStateException("Unknown mode: " + mode);
        }
    }

    private boolean evaluate(Object target) {
        if (target == null) {
            return false;
        }
        if (target instanceof MatchContext) {
            return criteria.matches(expression, (MatchContext) target);
        }
        return criteria.matches(expression, target);
    }
}
