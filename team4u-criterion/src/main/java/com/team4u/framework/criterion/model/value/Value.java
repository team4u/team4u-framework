package com.team4u.framework.criterion.model.value;

import com.team4u.framework.criterion.MatchContext;

/**
 * 通用值提供者接口
 * <p>
 * 用于统一屏蔽"字面量"和"变量"的区别：
 * <ul>
 * <li>静态值 (FixedValue)：解析时确定，运行时直接返回</li>
 * <li>动态值 (VariableValue)：解析时存储变量名，运行时从 MatchContext 获取</li>
 * </ul>
 *
 * @param <T> 值的类型
 */
public interface Value<T> {

    /**
     * 获取值
     *
     * @param context 匹配上下文（用于解析变量）
     * @return 实际值
     */
    T get(MatchContext context);
}
