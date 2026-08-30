package com.team4u.framework.router.api.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 路由规则定义 (Routing Rule Model)
 * <p>
 * 该类描述了单条匹配逻辑。它将一个“匹配条件”映射到一个“执行结果值”。
 * 不同的路由器类型对 {@code condition} 有不同的解释：
 * <ul>
 *   <li><b>映射路由 (Map)</b>：{@code condition} 为具体的匹配键名。</li>
 *   <li><b>表达式路由 (Expression)</b>：{@code condition} 为计算表达式字符串。</li>
 *   <li><b>权重路由 (Weight)</b>：{@code condition} 为该规则分得的数字权重。</li>
 * </ul>
 * </p>
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
