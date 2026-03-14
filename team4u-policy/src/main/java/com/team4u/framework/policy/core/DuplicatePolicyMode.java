package com.team4u.framework.policy.core;

/**
 * 重复策略注册模式。
 */
public enum DuplicatePolicyMode {

    /**
     * 允许同一实现类的多个实例并存，按注册顺序参与同优先级排序。
     */
    APPEND,

    /**
     * 同一实现类重复注册时，后注册实例替换先注册实例。
     */
    REPLACE_BY_CLASS
}
