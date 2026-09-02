package com.team4u.framework.criterion.parser.token;

import com.team4u.framework.parser.SourceSpan;
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
     * 源码空间范围定位
     */
    private SourceSpan span;

    public SourceSpan span() {
        return span;
    }
}
