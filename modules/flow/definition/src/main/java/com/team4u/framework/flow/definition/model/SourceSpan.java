package com.team4u.framework.flow.definition.model;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 源码文本位置区间（Source Span），记录节点在 DSL 文本中的起止行号与列号。
 *
 * @author jay.wu
 */
@Getter
@Accessors(fluent = true)
@EqualsAndHashCode
public final class SourceSpan implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 未知或合成位置常量。 */
    public static final SourceSpan UNKNOWN = new SourceSpan(null, 0, 0, 0, 0);

    private final String source;
    private final int startLine;
    private final int startColumn;
    private final int endLine;
    private final int endColumn;

    public SourceSpan(String source, int startLine, int startColumn, int endLine, int endColumn) {
        this.source = source;
        this.startLine = startLine;
        this.startColumn = startColumn;
        this.endLine = endLine;
        this.endColumn = endColumn;
    }

    /**
     * 格式化输出人类可读的位置字符串（如 "order.flow:18:9" 或 "18:9"）。
     *
     * @return 格式化位置字符串
     */
    public String format() {
        if (this == UNKNOWN || (startLine == 0 && startColumn == 0)) {
            return source != null ? source : "<unknown>";
        }
        if (source != null && !source.isEmpty()) {
            return source + ":" + startLine + ":" + startColumn;
        }
        return startLine + ":" + startColumn;
    }

    @Override
    public String toString() {
        return format();
    }
}
