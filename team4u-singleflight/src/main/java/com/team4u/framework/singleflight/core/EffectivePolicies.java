package com.team4u.framework.singleflight.core;

import com.team4u.framework.criterion.MatchContext;
import com.team4u.framework.singleflight.api.SingleFlightExecution;
import com.team4u.framework.singleflight.config.ContentionPolicy;
import com.team4u.framework.singleflight.config.RuleMissingPolicy;
import com.team4u.framework.singleflight.config.SingleFlightRule;
import com.team4u.framework.singleflight.config.StoreFailurePolicy;
import com.team4u.framework.singleflight.policy.SingleFlightCondition;

import java.util.Set;

/**
 * 生效策略推导：把规则声明与执行上下文组合为运行期实际采用的策略。
 * <p>
 * 集中承载「显式配置优先、省略按语义推导」的规则，避免推导逻辑散落在
 * 引擎与协调器的多个分支里——推导口径变更时只改这一处。
 * </p>
 *
 * @author jay.wu
 */
final class EffectivePolicies {

    private EffectivePolicies() {
    }

    /**
     * 生效的存储故障策略：规则显式配置优先；
     * 省略时 FAIL_FAST（本身即拒绝语义）默认 FAIL_CLOSED，WAIT / FALLBACK 默认 PASS_THROUGH。
     */
    static StoreFailurePolicy storeFailure(SingleFlightRule rule) {
        if (rule.getOnStoreFailure() != null) {
            return rule.getOnStoreFailure();
        }
        return rule.getContention() == ContentionPolicy.FAIL_FAST
                ? StoreFailurePolicy.FAIL_CLOSED : StoreFailurePolicy.PASS_THROUGH;
    }

    /**
     * 命中跳过条件：以参数名 Map 为匹配对象与属性上下文执行 skipWhen 匹配，
     * 命中则完全绕过协调与缓存。
     */
    static boolean matchesSkip(CompiledRule rule, SingleFlightExecution<?> execution) {
        MatchContext context = MatchContext.of(execution.getArguments());
        context.setAttributes(execution.getArguments());
        return rule.skipWhen().matches(context);
    }

    /**
     * 结果可缓存判定上下文：以加载结果为匹配对象、参数名 Map 为属性上下文。
     */
    static MatchContext resultContext(Object result, SingleFlightExecution<?> execution) {
        MatchContext context = MatchContext.of(result);
        context.setAttributes(execution.getArguments());
        return context;
    }

    /**
     * 校验条件表达式中的变量在执行上下文中可解析（要求调用方提供参数名集合，
     * 如代理边界通过 -parameters 拿到的真实方法参数名），让配置笔误尽早失败。
     */
    static void validateCriterionVariables(SingleFlightCondition condition,
                                           SingleFlightExecution<?> execution,
                                           String field) {
        if (condition == null || execution.getParameterNames().isEmpty()
                || !"skipWhen".equals(field)) {
            return;
        }
        Set<String> knownNames = execution.getParameterNames();
        for (String name : condition.variableNames()) {
            String variableName = name.startsWith("$") ? name.substring(1) : name;
            if (!knownNames.contains(variableName)) {
                throw new com.team4u.framework.singleflight.api.SingleFlightConfigException(
                        "Singleflight variable is not resolvable|field=" + field
                                + "|variable=" + name);
            }
        }
    }

    /**
     * 执行期组合校验：void 方法没有可传递的结果（禁止缓存与非 FAIL_FAST 竞争）；
     * 基本类型返回值无法承载显式 null 降级。
     */
    static void validateRuleForExecution(SingleFlightRule rule,
                                         SingleFlightExecution<?> execution) {
        boolean voidReturn = void.class.equals(execution.getReturnType())
                || Void.TYPE.equals(execution.getReturnType());
        if (voidReturn && (rule.isCacheEnabled()
                || rule.getContention() != ContentionPolicy.FAIL_FAST)) {
            throw new com.team4u.framework.singleflight.api.SingleFlightConfigException(
                    "void method requires cacheEnabled=false and contention=FAIL_FAST");
        }
        if (rule.getContention() == ContentionPolicy.FALLBACK && rule.getFallback() != null
                && rule.getFallback().isNull() && isPrimitive(execution.getReturnType())) {
            throw new com.team4u.framework.singleflight.api.SingleFlightConfigException(
                    "Primitive return type does not allow explicit null fallback|returnType="
                            + execution.getReturnType().getTypeName());
        }
        if (rule.getErrorFallback() != null && rule.getErrorFallback().isNull()
                && isPrimitive(execution.getReturnType())) {
            throw new com.team4u.framework.singleflight.api.SingleFlightConfigException(
                    "Primitive return type does not allow explicit null errorFallback|returnType="
                            + execution.getReturnType().getTypeName());
        }
    }

    private static boolean isPrimitive(java.lang.reflect.Type type) {
        return type instanceof Class && ((Class<?>) type).isPrimitive();
    }
}
