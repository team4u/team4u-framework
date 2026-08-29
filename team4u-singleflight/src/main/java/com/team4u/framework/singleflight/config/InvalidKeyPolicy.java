package com.team4u.framework.singleflight.config;

/**
 * key 渲染失败策略：key 模板变量为 null 或渲染结果为空白时的处置方式。
 *
 * @author jay.wu
 */
public enum InvalidKeyPolicy {

    /**
     * 抛配置异常。渲染失败通常意味着调用上下文与规则预期不符，应尽早暴露。
     */
    ERROR,

    /**
     * 不做协调，直接执行加载函数。
     */
    PASS_THROUGH
}
