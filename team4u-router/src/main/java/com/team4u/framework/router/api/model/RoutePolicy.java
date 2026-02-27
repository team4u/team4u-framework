package com.team4u.framework.router.api.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import cn.hutool.core.convert.Convert;

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

    /**
     * 扩展属性 (路由器专用配置)
     */
    private Map<String, Object> ext = new HashMap<>();

    /**
     * 获取扩展属性值
     *
     * @param key          属性名
     * @param defaultValue 默认值 (同时也定义了返回值的类型)
     * @param <T>          泛型类型
     * @return 属性值或默认值
     */
    @SuppressWarnings("unchecked")
    public <T> T getExtProperty(String key, T defaultValue) {
        if (ext == null || !ext.containsKey(key)) {
            return defaultValue;
        }
        Object value = ext.get(key);
        if (value == null) {
            return defaultValue;
        }
        // 使用 Hutool 进行安全类型转换
        return (T) Convert.convert(defaultValue.getClass(), value);
    }
}
