package com.team4u.framework.flow.definition.type;

import java.io.Serializable;

/**
 * 流程类型系统类型引用抽象接口（Type Reference）。
 *
 * @author jay.wu
 */
public interface TypeRef extends Serializable {

    /**
     * 获取原始 Java Class 类型（若不可解析或动态类型则返回 Object.class）。
     *
     * @return 原始 Class
     */
    Class<?> rawType();

    /**
     * 获取人类可读的类型名称（如 "com.foo.Order" 或 "Resumed<Order, Signal>"）。
     *
     * @return 类型名称
     */
    String typeName();

    /**
     * 判断当前类型是否可从目标类型赋值（即当前类型是 targetType 的父类/超类型）。
     *
     * @param targetType 候选子类型
     * @return 若兼容则返回 true
     */
    boolean isAssignableFrom(TypeRef targetType);

    /** 通用任意类型常量。 */
    TypeRef ANY = of(Object.class);

    /**
     * 从 Java Class 构造类型引用。
     *
     * @param clazz Java 类型
     * @return Class 类型引用
     */
    static TypeRef of(Class<?> clazz) {
        return new ClassTypeRef(clazz);
    }

    /**
     * 构造挂起恢复信号复合类型引用：Resumed<V, S>。
     *
     * @param valueType  原流程值类型
     * @param signalType 恢复信号类型
     * @return 复合类型引用
     */
    static TypeRef resumed(TypeRef valueType, TypeRef signalType) {
        return new ResumedTypeRef(valueType, signalType);
    }

    /**
     * 构造失败恢复复合类型引用：Recovery<I>。
     *
     * @param inputType 初始输入类型
     * @return 复合类型引用
     */
    static TypeRef recovery(TypeRef inputType) {
        return new RecoveryTypeRef(inputType);
    }
}
