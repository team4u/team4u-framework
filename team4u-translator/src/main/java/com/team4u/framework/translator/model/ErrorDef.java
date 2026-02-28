package com.team4u.framework.translator.model;

import lombok.Data;

/**
 * 目标契约定义 (Target Error Definition)
 * <p>
 * 这是存在于配置中心（如 JSON）里的静态模板规则。
 * 路由匹配后将返回此定义的实例，作为渲染管线的依据。
 */
@Data
public class ErrorDef {

    /**
     * 暴露给外部的标准码 (如: "INVALID_PARAM")
     */
    private String code;

    /**
     * 国际化文案标识 (如: "err.order.timeout")
     */
    private String i18nKey;

    /**
     * 默认文案模板 (如: "订单超时，单号:${orderId}")
     */
    private String defaultMsg;

    /**
     * 动态日志级别管控 (如: "WARN", "ERROR")
     */
    private String logLevel;
}
