package com.team4u.framework.criterion.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import com.team4u.framework.criterion.model.value.Value;

import java.util.Collections;
import java.util.List;
import java.util.function.BiPredicate;

/**
 * 动态标准
 * <p>
 * 将匹配逻辑直接封装在对象中，实现解析期绑定
 */
@Getter
@RequiredArgsConstructor
public class DynamicCriterion extends Criterion implements ValueContainer {
    private final String operator;
    private final Value<?> value;
    /**
     * 绑定的匹配逻辑
     */
    private final BiPredicate<Object, Object> logic;

    @Override
    public List<Value<?>> values() {
        return Collections.singletonList(value);
    }
}
