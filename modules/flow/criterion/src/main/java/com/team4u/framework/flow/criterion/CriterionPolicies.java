package com.team4u.framework.flow.criterion;

import com.team4u.framework.flow.model.Failure;
import com.team4u.framework.flow.model.Reason;

/**
 * {@link CriterionPolicy} 工厂与便捷构建工具类。
 *
 * @author jay.wu
 */
public final class CriterionPolicies {

    private CriterionPolicies() {
    }

    /**
     * 创建准入放行策略：当输入满足表达式时放行，否则以 {@link Reason} 业务拒绝。
     *
     * @param expression 准入规则表达式（如 {@code "age >= 18 && verified == true"}）
     * @param <K>        路由键类型
     * @return 初始化的 {@link CriterionPolicy} 实例
     */
    public static <K> CriterionPolicy<K> permitIf(String expression) {
        return CriterionPolicy.<K>builder()
                .expression(expression)
                .mode(CriterionPolicy.Mode.PERMIT_IF)
                .action(CriterionAction.REJECT)
                .build();
    }

    /**
     * 创建准入放行策略（自定义拒绝原因）：当输入满足表达式时放行，否则以指定原因业务拒绝。
     *
     * @param expression    准入规则表达式
     * @param reasonCode    拒绝原因码
     * @param reasonMessage 拒绝描述信息
     * @param <K>           路由键类型
     * @return 初始化的 {@link CriterionPolicy} 实例
     */
    public static <K> CriterionPolicy<K> permitIf(String expression, String reasonCode, String reasonMessage) {
        return CriterionPolicy.<K>builder()
                .expression(expression)
                .mode(CriterionPolicy.Mode.PERMIT_IF)
                .action(CriterionAction.REJECT)
                .reasonFactory((ctx, key) -> Reason.of(reasonCode, reasonMessage))
                .build();
    }

    /**
     * 创建拦截拒绝策略：当输入满足表达式时以 {@link Reason} 业务拒绝短路，否则放行。
     *
     * @param expression 拦截规则表达式（如 {@code "blacklisted == true || riskScore > 80"}）
     * @param <K>        路由键类型
     * @return 初始化的 {@link CriterionPolicy} 实例
     */
    public static <K> CriterionPolicy<K> rejectIf(String expression) {
        return CriterionPolicy.<K>builder()
                .expression(expression)
                .mode(CriterionPolicy.Mode.REJECT_IF)
                .build();
    }

    /**
     * 创建拦截拒绝策略（自定义拒绝原因）：当输入满足表达式时以指定原因业务拒绝短路，否则放行。
     *
     * @param expression    拦截规则表达式
     * @param reasonCode    拒绝原因码
     * @param reasonMessage 拒绝描述信息
     * @param <K>           路由键类型
     * @return 初始化的 {@link CriterionPolicy} 实例
     */
    public static <K> CriterionPolicy<K> rejectIf(String expression, String reasonCode, String reasonMessage) {
        return CriterionPolicy.<K>builder()
                .expression(expression)
                .mode(CriterionPolicy.Mode.REJECT_IF)
                .reasonFactory((ctx, key) -> Reason.of(reasonCode, reasonMessage))
                .build();
    }

    /**
     * 创建故障失败策略：当输入满足表达式时以 {@link Failure} 系统故障抛出（可触发重试/容灾），否则放行。
     *
     * @param expression     故障规则表达式
     * @param failureCode    故障错误码
     * @param failureMessage 故障描述信息
     * @param <K>            路由键类型
     * @return 初始化的 {@link CriterionPolicy} 实例
     */
    public static <K> CriterionPolicy<K> failIf(String expression, String failureCode, String failureMessage) {
        return CriterionPolicy.<K>builder()
                .expression(expression)
                .mode(CriterionPolicy.Mode.FAIL_IF)
                .failureFactory((ctx, key) -> Failure.of(failureCode, failureMessage))
                .build();
    }
}
