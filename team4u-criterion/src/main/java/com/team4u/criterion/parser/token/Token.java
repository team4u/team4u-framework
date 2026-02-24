package com.team4u.criterion.parser.token;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 词法单元实体
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class Token {
    /**
     * 类型
     */
    private TokenType type;
    /**
     * 原始字符串内容
     */
    private String value;
    /**
     * 在表达式中的起始位置，用于精准报错
     */
    private int startPos;
}
