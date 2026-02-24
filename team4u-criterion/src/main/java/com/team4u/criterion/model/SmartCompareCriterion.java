package com.team4u.criterion.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import com.team4u.criterion.model.value.Value;

import java.util.Collections;
import java.util.List;

/**
 * 智能比较规则
 * <p>
 * 统一处理关系运算符（大于、小于、等于等）。
 * 在运行期（或编译期常量优化时）进行类型推断并比较。
 * 当无法直接使用强类型比较时，会尝试降级方案（如均转为 BigDecimal 或 String 进行比较）。
 * </p>
 *
 * @author jay.wu
 */
@Getter
@RequiredArgsConstructor
public class SmartCompareCriterion extends Criterion implements ValueContainer {
    private final String operator;
    private final Value<?> valueProvider;

    @Override
    public List<Value<?>> values() {
        return Collections.singletonList(valueProvider);
    }
}