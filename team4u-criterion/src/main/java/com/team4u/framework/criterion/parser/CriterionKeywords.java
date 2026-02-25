package com.team4u.framework.criterion.parser;

/**
 * 规则表达式保留字与操作符常量
 *
 * @author jay.wu
 */
public interface CriterionKeywords {
    /**
     * 逻辑且
     */
    String AND = "&&";
    /**
     * 逻辑或
     */
    String OR = "||";

    /**
     * 等于
     */
    String EQ = "==";
    /**
     * 不等于
     */
    String NE = "!=";
    /**
     * 大于
     */
    String GT = ">";
    /**
     * 大于等于
     */
    String GE = ">=";
    /**
     * 小于
     */
    String LT = "<";
    /**
     * 小于等于
     */
    String LE = "<=";
    /**
     * 赋值/等于（简写）
     */
    String ASSIGN = "=";
    /**
     * 正则匹配
     */
    String REGEX = "=~";

    /**
     * 左括号
     */
    String LEFT_PAREN = "(";
    /**
     * 右括号
     */
    String RIGHT_PAREN = ")";
    /**
     * 左中括号
     */
    String LEFT_BRACKET = "[";
    /**
     * 右中括号
     */
    String RIGHT_BRACKET = "]";
    /**
     * 逗号
     */
    String COMMA = ",";
}
