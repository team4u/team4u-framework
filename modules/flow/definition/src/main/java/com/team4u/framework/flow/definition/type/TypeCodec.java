package com.team4u.framework.flow.definition.type;

/**
 * 文本字面量与类型值双向转换编解码器（Type Codec）。
 *
 * @param <T> 目标数据类型
 * @author jay.wu
 */
public interface TypeCodec<T> {

    /**
     * 将 DSL 文本字面量字符串解码为类型值对象。
     *
     * @param literal 文本字面量
     * @return 解码后的值
     * @throws IllegalArgumentException 当字面量无法解析为目标类型时抛出
     */
    T decode(String literal);

    /**
     * 将类型值编码为文本字面量。
     *
     * @param value 类型值对象
     * @return 文本字面量
     */
    String encode(T value);
}
