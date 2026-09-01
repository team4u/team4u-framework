package com.team4u.framework.flow.definition.model;

import java.io.Serializable;

/**
 * 步骤或作用域修饰器规范接口（Step / Scope Modifier Spec）。
 *
 * @author jay.wu
 */
public interface ModifierSpec extends Serializable {

    /**
     * 获取修饰器在源码中的位置区间。
     *
     * @return 源码位置区间
     */
    SourceSpan span();
}
