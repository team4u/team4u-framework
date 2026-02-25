package com.team4u.framework.criterion.model;

import lombok.Data;
import lombok.EqualsAndHashCode;
import com.team4u.framework.criterion.model.value.Value;

import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

/**
 * 通用 Comparable 区间匹配规则
 */
@Data
@EqualsAndHashCode(callSuper = false)
public class BetweenCriterion extends Criterion implements ValueContainer {

    private final Value<?> lowerProvider;
    private final Value<?> upperProvider;
    private final boolean includeLower;
    private final boolean includeUpper;
    private final Function<Object, Comparable<?>> typeConverter;

    @Override
    public List<Value<?>> values() {
        return Arrays.asList(lowerProvider, upperProvider);
    }

    @Override
    public String toString() {
        if (getExpression() != null) {
            return getExpression();
        }
        return "between [" + lowerProvider + ", " + upperProvider + "]";
    }
}
