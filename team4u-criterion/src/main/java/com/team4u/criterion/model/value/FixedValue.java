package com.team4u.criterion.model.value;

import lombok.AllArgsConstructor;
import lombok.Getter;
import com.team4u.criterion.MatchContext;

/**
 * 静态值实现
 * <p>
 * 解析时确定值，运行时直接返回。
 *
 * @param <T> 值的类型
 */
@Getter
@AllArgsConstructor
public class FixedValue<T> implements Value<T> {

    /**
     * 固定的值
     */
    private final T value;

    @Override
    public T get(MatchContext context) {
        return value;
    }

    @Override
    public String toString() {
        return String.valueOf(value);
    }
}
