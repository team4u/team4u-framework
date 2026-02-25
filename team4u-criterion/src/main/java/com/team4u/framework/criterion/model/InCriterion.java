package com.team4u.framework.criterion.model;

import lombok.Getter;
import com.team4u.framework.criterion.model.value.FixedValue;
import com.team4u.framework.criterion.model.value.Value;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 集合包含规则
 * <p>
 * 支持静态值和动态变量：
 * <ul>
 * <li>role in ['admin', 'user'] - 静态值</li>
 * <li>id in [1, 2, $specialId] - 部分静态部分变量</li>
 * <li>it in group - 集合变量</li>
 * </ul>
 */
@Getter
public class InCriterion extends Criterion implements ValueContainer {
    private final List<Value<?>> values;
    private final boolean not;

    public InCriterion(List<? extends Value<?>> values, boolean not) {
        this.values = new ArrayList<>(values);
        this.not = not;
    }

    public static InCriterion of(Collection<?> values, boolean not) {
        List<Value<?>> valueList = values.stream()
                .map(v -> (Value<?>) new FixedValue<>(v))
                .collect(Collectors.toList());
        return new InCriterion(valueList, not);
    }

    @Override
    public List<Value<?>> values() {
        return values;
    }
}