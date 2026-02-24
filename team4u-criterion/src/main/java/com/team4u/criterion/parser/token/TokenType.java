package com.team4u.criterion.parser.token;

/**
 * 词法单元类型
 */
public enum TokenType {
    /**
     * 标识符/关键字 (如 age, it, contains, null, true)
     */
    IDENTIFIER,
    /**
     * 数值字面量 (如 18, -3.14)
     */
    NUMBER,
    /**
     * 字符串字面量 (包含引号，如 'admin')
     */
    STRING,
    /**
     * 操作符 (如 ==, >=, &&, ||, =~)
     */
    OPERATOR,
    /**
     * 界定符 (如 (, ), [, ], ,)
     */
    DELIMITER
}
