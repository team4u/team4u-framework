package com.team4u.criterion.model;

import lombok.Getter;
import com.team4u.criterion.model.value.FixedValue;
import com.team4u.criterion.model.value.Value;

import java.util.Collections;
import java.util.List;

/**
 * 集合包含元素规则
 * <p>
 * 用于判断属性（集合类型）是否包含指定值。
 * 支持静态值和动态变量：
 * <ul>
 * <li>roles contains 'admin' - 静态值</li>
 * <li>roles contains $requiredRole - 动态变量</li>
 * </ul>
 */
@Getter
public class ContainsCriterion extends Criterion implements ValueContainer {
    private final Value<?> valueProvider;

    public ContainsCriterion(Value<?> valueProvider) {
        this.valueProvider = valueProvider;
    }

    /**
     * 便捷构造方法（兼容静态值用法）
     */
    public ContainsCriterion(Object value) {
        this(new FixedValue<>(value));
    }

    @Override
    public List<Value<?>> values() {
        return Collections.singletonList(valueProvider);
    }
}