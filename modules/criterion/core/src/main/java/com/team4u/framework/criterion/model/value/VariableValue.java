package com.team4u.framework.criterion.model.value;

import com.team4u.framework.base.convert.ConvertUtil;
import com.team4u.framework.criterion.MatchContext;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 动态变量实现
 * <p>
 * 解析时存储去除了 '$' 前缀的真实变量名。
 * 运行时从 MatchContext 的 Attributes 中获取。
 *
 * @param <T> 值的类型
 */
@Getter
@AllArgsConstructor
public class VariableValue<T> implements Value<T> {

    /**
     * 变量名
     */
    private final String variableName;

    /**
     * 目标类型（用于运行时类型转换）
     */
    private final Class<T> targetType;

    @Override
    public T get(MatchContext context) {
        if (context == null) {
            return null;
        }
        // 从上下文属性中获取变量值
        Object raw = context.getAttribute(variableName);
        return ConvertUtil.convert(targetType, raw, null);
    }

    @Override
    public String toString() {
        // 重写 toString，还原表达式中的形态，方便 Trace 和日志打印
        return "$" + variableName;
    }
}
