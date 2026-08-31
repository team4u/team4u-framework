package com.team4u.framework.flow.criterion;

import com.team4u.framework.criterion.Criteria;
import com.team4u.framework.criterion.MatchContext;
import com.team4u.framework.criterion.parser.CriterionParseException;
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
 *   <li>{@link Mode#PERMIT_IF}：匹配表达式则放行，不匹配则拦截（拒绝或失败，由 {@code action} 决定）；</li>
 *   <li>{@link Mode#REJECT_IF}：匹配表达式则以 {@link Reason} 业务拒绝短路，不匹配则放行；</li>
 *   <li>{@link Mode#FAIL_IF}：匹配表达式则以 {@link Failure} 系统故障抛出，不匹配则放行。</li>
 * </ul>
 * </p>
 *
 * <p><b>求值异常契约（fail-closed）：</b>表达式求值抛出的任何运行时异常都会向上传播，
 * 由流程引擎捕获并转化为 {@code POLICY_EXCEPTION}（Failed）——即求值失败按"拦截"处理而非放行。</p>
 *
 * <p><b>构造期校验：</b>表达式在构造时即执行一次编译（{@code criteria.compileExpression}），
 * 非法表达式在构建期立即抛出 {@link CriterionParseException}（fail-fast），而非等到首次求值。</p>
 *
 * @param <K> 策略路由键类型
 * @author jay.wu
 */
@Getter
public final class CriterionPolicy<K> implements Policy<K> {

    /**
     * PERMIT_IF 模式未指定拒绝原因时的默认拒绝码
     */
    public static final String DEFAULT_REJECT_CODE = "CRITERION_REJECTED";

    /**
     * PERMIT_IF 模式未指定故障信息时的默认故障码
     */
    public static final String DEFAULT_FAILURE_CODE = "CRITERION_FAILED";

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

        // REJECT_IF/FAIL_IF 模式下拦截产物类型由模式唯一决定，显式指定的 action 无效：构造期即拒绝误用
        if (this.mode == Mode.REJECT_IF && action == CriterionAction.FAIL) {
            throw new IllegalArgumentException(
                    "action=FAIL is not applicable to mode REJECT_IF (it always rejects with Reason); "
                            + "use mode FAIL_IF or remove the action");
        }
        if (this.mode == Mode.FAIL_IF && action == CriterionAction.REJECT) {
            throw new IllegalArgumentException(
                    "action=REJECT is not applicable to mode FAIL_IF (it always fails with Failure); "
                            + "use mode REJECT_IF or remove the action");
        }

        // 构造期预编译表达式：非法表达式 fail-fast，而非等到首次求值
        this.criteria.compileExpression(this.expression);

        this.targetExtractor = targetExtractor;
        this.reasonFactory = reasonFactory != null ? reasonFactory :
                (ctx, key) -> Reason.of(DEFAULT_REJECT_CODE, "Rule rejected by expression: " + expression);
        this.failureFactory = failureFactory != null ? failureFactory :
                (ctx, key) -> Failure.of(DEFAULT_FAILURE_CODE, "Rule failed by expression: " + expression);
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
