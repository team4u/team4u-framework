package com.team4u.framework.flow.definition.property;

import com.team4u.framework.flow.definition.type.TypeRef;

/**
 * 预编译的属性读取算子（Compiled Reader）。
 *
 * @author jay.wu
 */
public interface CompiledReader {

    /**
     * 读取结果类型。
     *
     * @return 目标属性的 TypeRef
     */
    TypeRef resultType();

    /**
     * 从根对象读取属性值。
     *
     * @param root 根对象
     * @return 提取出的属性值
     */
    Object read(Object root);
}
