package com.team4u.framework.flow.definition.property;

import com.team4u.framework.flow.definition.type.TypeRef;

/**
 * 预编译的属性写回算子（Compiled Writer）。
 *
 * @author jay.wu
 */
public interface CompiledWriter {

    /**
     * 写回后根对象的最终结果类型。
     *
     * @return 根对象的 TypeRef
     */
    TypeRef resultType();

    /**
     * 将目标属性值写回根对象。
     *
     * @param root  根对象
     * @param value 待写入的属性值
     * @return 更新后的根对象
     */
    Object write(Object root, Object value);
}
