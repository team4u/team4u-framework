package com.team4u.framework.flow.criterion;

import com.team4u.framework.criterion.Criteria;

import java.util.function.Function;

/**
 * {@link CriterionPredicate} 工厂与便捷构建工具类。
 *
 * @author jay.wu
 */
public final class CriterionPredicates {

    private CriterionPredicates() {
    }

    /**
     * 基于表达式创建匹配谓词（默认使用 {@link Criteria#global()} 全局规则引擎）。
     *
     * @param expression 规则表达式（如 {@code "amount >= 100 && status == 'PAID'"}）
     * @param <T>        输入数据类型
     * @return 初始化的 {@link CriterionPredicate} 实例
     */
    public static <T> CriterionPredicate<T> of(String expression) {
        return new CriterionPredicate<>(expression, Criteria.global(), null);
    }

    /**
     * 基于表达式与目标提取器创建匹配谓词。
     *
     * @param expression      规则表达式
     * @param targetExtractor 目标对象提取器
     * @param <T>             输入上下文类型
     * @return 初始化的 {@link CriterionPredicate} 实例
     */
    public static <T> CriterionPredicate<T> of(String expression, Function<T, Object> targetExtractor) {
        return new CriterionPredicate<>(expression, Criteria.global(), targetExtractor);
    }

    /**
     * 基于自定义 {@link Criteria} 引擎与表达式创建匹配谓词。
     *
     * @param expression 规则表达式
     * @param criteria   自定义规则引擎实例
     * @param <T>        输入数据类型
     * @return 初始化的 {@link CriterionPredicate} 实例
     */
    public static <T> CriterionPredicate<T> of(String expression, Criteria criteria) {
        return new CriterionPredicate<>(expression, criteria, null);
    }

    /**
     * 基于自定义 {@link Criteria} 引擎、表达式与目标提取器创建匹配谓词。
     *
     * @param expression      规则表达式
     * @param criteria        自定义规则引擎实例
     * @param targetExtractor 目标对象提取器
     * @param <T>             输入上下文类型
     * @return 初始化的 {@link CriterionPredicate} 实例
     */
    public static <T> CriterionPredicate<T> of(String expression, Criteria criteria, Function<T, Object> targetExtractor) {
        return new CriterionPredicate<>(expression, criteria, targetExtractor);
    }
}
