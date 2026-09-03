package com.team4u.framework.flow.definition.property;

import com.team4u.framework.flow.definition.model.PropertyPath;
import com.team4u.framework.flow.definition.type.TypeRef;

/**
 * 属性访问编译器 SPI 接口（Property Access Compiler）。
 *
 * <p>负责将语义化的 {@link PropertyPath} 针对指定的输入根类型编译为强类型、高性能的读取器与写回器。
 * 支持通过自定义实现接入 JSON Pointer、Jackson、Protobuf 等复杂领域模型。</p>
 *
 * @author jay.wu
 */
public interface PropertyAccessCompiler {

    /**
     * 编译属性读取算子。
     *
     * @param rootType 根对象类型
     * @param path     属性访问路径
     * @return 编译后的属性读取器
     */
    CompiledReader compileReader(TypeRef rootType, PropertyPath path);

    /**
     * 编译属性写回算子。
     *
     * @param rootType  根对象类型
     * @param path      属性访问路径
     * @param valueType 待写入的值类型
     * @return 编译后的属性写回器
     */
    CompiledWriter compileWriter(TypeRef rootType, PropertyPath path, TypeRef valueType);
}
