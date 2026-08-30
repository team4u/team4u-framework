package com.team4u.framework.criterion.compiler;

import com.team4u.framework.criterion.MatchContext;
import com.team4u.framework.criterion.model.value.Value;

/**
 * 编译后优化的值获取器
 * <p>
 * 用于在编译期将 {@link Value} 对象转换为无分支的高效取值器。
 * <p>
 * 根据原始 Value 的类型，运行时 get() 方法可能是：
 * <ul>
 * <li>直接返回编译期确定的常量（FixedValue 优化路径）</li>
 * <li>运行时从 MatchContext 中动态获取并转换值</li>
 * </ul>
 *
 * @param <R> 目标转换类型
 * @author jay.wu
 */
@FunctionalInterface
public interface CompiledValue<R> {

    /**
     * 获取已转换好的最终目标值
     *
     * @param context 匹配上下文（用于解析变量）
     * @return 已转换的目标值
     */
    R get(MatchContext context);
}
