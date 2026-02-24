package com.team4u.criterion.parser.handler;

import com.team4u.criterion.model.Criterion;
import com.team4u.criterion.parser.CriterionParser;
import com.team4u.criterion.parser.SyntaxHandler;

import java.util.function.Function;

/**
 * 字符串模式匹配语法处理器
 */
public class StringPatternSyntaxHandler implements SyntaxHandler {

    /**
     * LIKE 关键字
     */
    public static final String KEYWORD_LIKE = "like";
    /**
     * 正则关键字
     */
    public static final String KEYWORD_REGEX = "=~";

    private final String keyword;
    private final Function<String, Criterion> criterionFactory;

    public StringPatternSyntaxHandler(String keyword, Function<String, Criterion> criterionFactory) {
        this.keyword = keyword;
        this.criterionFactory = criterionFactory;
    }

    @Override
    public Criterion tryParse(String subject, CriterionParser.Context context) {
        if (!context.match(keyword)) {
            return null;
        }

        String token = context.consumeValue();
        String pattern = context.parseValueToken(token);

        return context.wrapProperty(subject, criterionFactory.apply(pattern));
    }

    /**
     * 正则语法处理器
     */
    public static class Regex extends StringPatternSyntaxHandler {
        public Regex(String keyword, Function<String, Criterion> criterionFactory) {
            super(keyword, criterionFactory);
        }
    }

    /**
     * 通配符语法处理器
     */
    public static class Like extends StringPatternSyntaxHandler {
        public Like(String keyword, Function<String, Criterion> criterionFactory) {
            super(keyword, criterionFactory);
        }
    }
}