package com.team4u.framework.singleflight.config;

/**
 * 规则缺失策略：point 没有对应配置规则时的处置方式。
 * <p>
 * 仅通过全局配置键 {@code team4u.singleflight.on_rule_missing} 配置，规则内同名字段不参与裁决。
 * </p>
 *
 * @author jay.wu
 */
public enum RuleMissingPolicy {

    /**
     * 直接执行加载函数并记 warn 日志。默认值，保障“配置未就绪不阻断业务”。
     */
    PASS_THROUGH,

    /**
     * 抛配置异常。适用于“所有 point 必须显式声明规则”的强约束场景。
     */
    ERROR
}
