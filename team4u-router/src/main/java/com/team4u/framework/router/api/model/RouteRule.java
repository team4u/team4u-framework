package com.team4u.framework.router.api.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 路由规则
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RouteRule {

    /**
     * 路由条件
     * 对于映射路由，这是匹配的键；对于表达式路由，这是匹配的表达式
     */
    private String condition;

    /**
     * 路由结果值
     */
    private Object value;
}
