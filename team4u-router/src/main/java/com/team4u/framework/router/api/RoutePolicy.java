package com.team4u.framework.router.api;

import lombok.Data;

import java.util.LinkedHashMap;

/**
 * 路由配置策略
 */
@Data
public class RoutePolicy {

    /**
     * 路由唯一标识
     */
    private String id;

    /**
     * 路由类型："map" 或 "expression"
     */
    private String type;

    /**
     * 路由规则
     * 使用 LinkedHashMap 以保证有序，特别是对于表达式路由
     */
    private LinkedHashMap<String, Object> rules = new LinkedHashMap<>();
}
