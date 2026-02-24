package com.team4u.criterion.parser.handler;

import com.team4u.criterion.model.Criterion;
import com.team4u.criterion.model.SmartCompareCriterion;
import com.team4u.criterion.model.value.Value;
import com.team4u.criterion.model.value.ValueFactory;
import com.team4u.criterion.parser.CriterionKeywords;
import com.team4u.criterion.parser.CriterionParser;
import com.team4u.criterion.parser.SyntaxHandler;
import com.team4u.criterion.parser.token.Token;
import com.team4u.criterion.parser.token.TokenType;
import com.team4u.criterion.util.FastNumberUtil;

import java.util.Arrays;
import java.util.List;

/**
 * 关系操作符语法处理器
 * <p>
 * 拦截所有比较运算符：{@code >, >=, <, <=, ==, !=, =}
 * 由于在编译期无法绝对确认变量的具体类型，因此统一收集并推迟到运行期利用
 * {@link SmartCompareCriterion} 进行动态类型推断和验证。
 * </p>
 *
 * @author jay.wu
 */
public class RelationalOperatorSyntaxHandler implements SyntaxHandler {

    private static final List<String> OPERATORS = Arrays.asList(
            CriterionKeywords.GT,
            CriterionKeywords.GE,
            CriterionKeywords.LT,
            CriterionKeywords.LE,
            CriterionKeywords.EQ,
            CriterionKeywords.NE,
            CriterionKeywords.ASSIGN);

    @Override
    public int priority() {
        return Integer.MAX_VALUE;
    }

    @Override
    public Criterion tryParse(String subject, CriterionParser.Context context) {
        Token operatorToken = context.peekToken(0);
        Token valueToken = context.peekToken(1);

        // 只要是这几个关系操作符，全部接管
        if (operatorToken == null || valueToken == null || !OPERATORS.contains(operatorToken.getValue())) {
            return null;
        }

        // 根据 token 类型安全消费操作符
        if (operatorToken.getType() == TokenType.IDENTIFIER) {
            context.consumeSubject();
        } else {
            context.consumeOperator();
        }
        String rawValue = context.consumeValue();

        // 统一使用 Object.class，因为我们要推迟到运行时通过 SmartCompareCriterion 的 Compiler 进行类型推断判断。
        Value<Object> valueProvider = ValueFactory.create(
                rawValue,
                s -> (valueToken.getType() == TokenType.NUMBER) ? FastNumberUtil.toNumber(s) : s,
                Object.class);

        return context.wrapProperty(subject, new SmartCompareCriterion(operatorToken.getValue(), valueProvider));
    }
}