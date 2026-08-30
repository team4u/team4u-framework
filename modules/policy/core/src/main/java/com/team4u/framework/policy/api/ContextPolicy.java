package com.team4u.framework.policy.api;

/**
 * 基于上下文匹配的策略接口
 *
 * @param <C> 上下文类型
 */
public interface ContextPolicy<C> extends Comparable<ContextPolicy<C>> {

    /**
     * 最高优先级
     */
    int HIGHEST = Integer.MIN_VALUE;

    /**
     * 高优先级
     */
    int HIGH = -1000;

    /**
     * 普通优先级
     */
    int NORMAL = 0;

    /**
     * 低优先级
     */
    int LOW = 1000;

    /**
     * 最低优先级
     */
    int LOWEST = Integer.MAX_VALUE;

    /**
     * 是否支持处理该上下文
     *
     * @param context 匹配上下文
     * @return true 如果是，否则 false
     */
    boolean supports(C context);

    /**
     * 优先级 (默认普通优先级)
     *
     * @return 优先级数字，越小优先级越高
     */
    default int priority() {
        return NORMAL;
    }

    /**
     * 默认按优先级升序排序
     *
     * @param o 另一个策略
     * @return 比较结果
     */
    @Override
    default int compareTo(ContextPolicy<C> o) {
        return Integer.compare(this.priority(), o.priority());
    }
}
