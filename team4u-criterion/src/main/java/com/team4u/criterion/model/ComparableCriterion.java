package com.team4u.criterion.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import com.team4u.criterion.model.value.Value;

import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/**
 * 通用 Comparable 比较规则
 * <p>
 * 只要实现了 Comparable 接口的对象（Date, String, BigDecimal, Version等）都可以用此规则比较。
 */
@Getter
@RequiredArgsConstructor
public class ComparableCriterion extends Criterion implements ValueContainer {

    private final String operator;
    private final Value<String> expectedValueProvider;
    /**
     * 类型转换器：负责将 实际值(Object) 和 预期值(String) 统一转为目标 Comparable 类型
     */
    private final Function<Object, Comparable<?>> typeConverter;

    @Override
    public List<Value<?>> values() {
        return Collections.singletonList(expectedValueProvider);
    }
}