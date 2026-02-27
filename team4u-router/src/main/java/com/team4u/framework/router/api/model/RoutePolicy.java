package com.team4u.framework.router.api.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

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
     * 使用 List 以保证顺序，特别是对于表达式路由
     */
    private List<RouteRule> rules = new ArrayList<>();

    /**
     * 兜底路由值
     * 当所有规则都不匹配时，返回该值
     */
    private Object fallbackValue;
}
