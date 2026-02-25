package com.team4u.framework.criterion.parser.handler;

import com.team4u.framework.criterion.model.ComparableCriterion;
import com.team4u.framework.criterion.model.Criterion;
import com.team4u.framework.criterion.model.convert.ValueConverter;
import com.team4u.framework.criterion.model.convert.ValueConverterRegistry;
import com.team4u.framework.criterion.model.value.Value;
import com.team4u.framework.criterion.parser.CriterionParser;
import com.team4u.framework.criterion.parser.SyntaxHandler;

import java.util.function.Supplier;

/**
 * 转换器语法处理器 (后缀风格)
 * <p>
 * 语法：subject:converter operator value
 * 示例：createTime:date > '2023-01-01'
 * 示例：appVersion:version >= '1.0.0'
 *
 * @author jay.wu
 */
public class ValueConverterSyntaxHandler implements SyntaxHandler {

    private final Supplier<ValueConverterRegistry> converterRegistrySupplier;

    public ValueConverterSyntaxHandler() {
        this(ValueConverterRegistry::new);
    }

    public ValueConverterSyntaxHandler(Supplier<ValueConverterRegistry> converterRegistrySupplier) {
        this.converterRegistrySupplier = converterRegistrySupplier;
    }

    @Override
    public Criterion tryParse(String subject, CriterionParser.Context context) {
        // 1. 检查是否存在冒号 ":"
        if (!context.match(":")) {
            return null;
        }

        // 2. 消费转换器 ID (例如 "date")
        String converterId = context.consumeValue();

        // 3. 查找转换器，如果没有找到则回退（返回 null 让后续处理器尝试，例如 SimplifySyntaxHandler）
        ValueConverter converter = converterRegistrySupplier.get().policyOf(converterId);
        if (converter == null) {
            throw new IllegalArgumentException("Unknown value converter: " + converterId);
        }

        // 4. 消费操作符
        String operator = context.consumeOperator();

        // 5. 构建比较规则
        Criterion leaf;
        if ("between".equalsIgnoreCase(operator)) {
            // 复用 Between 的解析逻辑
            leaf = BetweenSyntaxHandler.parseBody(context, String.class, s -> s, converter);
        } else {
            // 常规比较
            Value<String> expectedValue = context.consumeAsValue(String.class, s -> s);
            leaf = new ComparableCriterion(operator, expectedValue, converter);
        }

        // 6. 包裹属性提取 (subject 即为属性名，如 createTime)
        return context.wrapProperty(subject, leaf);
    }
}