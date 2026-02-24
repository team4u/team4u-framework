package com.team4u.criterion.parser.handler;

import cn.hutool.core.convert.Convert;
import com.team4u.criterion.model.Criterion;
import com.team4u.criterion.model.value.FixedValue;
import com.team4u.criterion.model.value.Value;
import com.team4u.criterion.model.value.ValueFactory;
import com.team4u.criterion.parser.CriterionParser;
import com.team4u.criterion.parser.SyntaxHandler;

import java.util.function.BiFunction;

/**
 * 通用关键字-值处理器
 * <p>
 * 模式：Subject Keyword Value (例如: it prob 0.3)
 * 支持静态值和动态变量。
 */
public class SimpleKeywordHandler implements SyntaxHandler {

    private final String keyword;
    private final Class<?> valueType;
    // 工厂函数：(Subject, Value) -> Criterion
    private final BiFunction<String, Value<?>, Criterion> factory;

    public SimpleKeywordHandler(String keyword, Class<?> valueType, BiFunction<String, Value<?>, Criterion> factory) {
        this.keyword = keyword;
        this.valueType = valueType;
        this.factory = factory;
    }

    @Override
    public Criterion tryParse(String subject, CriterionParser.Context context) {
        if (!context.match(keyword)) {
            return null;
        }

        String token = context.consumeValue();

        @SuppressWarnings("unchecked")
        Value<?> value = ValueFactory.create(
                token,
                s -> Convert.convert(valueType, s),
                (Class<Object>) valueType
        );

        // 对于数值类型，如果值是 FixedValue 且为 null，说明类型转换失败
        if (Number.class.isAssignableFrom(valueType)) {
            if (value instanceof FixedValue) {
                FixedValue<?> fixedValue = (FixedValue<?>) value;
                if (fixedValue.get(null) == null) {
                    throw new RuntimeException(keyword + " value must be a valid number: " + token);
                }
            }
        }

        return context.wrapProperty(subject, factory.apply(subject, value));
    }
}