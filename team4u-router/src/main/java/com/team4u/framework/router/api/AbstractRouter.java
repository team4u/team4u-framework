package com.team4u.framework.router.api;

import cn.hutool.core.convert.Convert;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 抽象路由器，处理通用的类型转换和本地缓存逻辑
 *
 * @author jay.wu
 */
public abstract class AbstractRouter implements Router {

    /**
     * 路由器级别的本地缓存，生命周期随 Router 销毁而销毁，杜绝内存泄漏
     * Key 为 targetType.getName() + "@" + System.identityHashCode(rawValue)，Value
     * 为转换后的 Bean
     */
    private final ConcurrentMap<String, Object> convertedCache = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    @Override
    public <T> RouteResult<T> route(Object request, Class<T> targetType) {
        // 调用子类（MapRouter/ExpressionRouter）的匹配逻辑
        RouteResult<?> result = route(request);
        if (result == null || result.isNotMatch()) {
            return RouteResult.unmatch();
        }

        Object rawValue = result.getValue();
        if (rawValue == null) {
            // 透传 matchedCondition
            return RouteResult.matched(null, result.getMatchedCondition());
        }

        if (targetType != null && !targetType.isInstance(rawValue)) {
            // 利用 targetType 的类名 + 原对象的内存地址生成唯一 Key
            String cacheKey = targetType.getName() + "@" + System.identityHashCode(rawValue);

            // computeIfAbsent 保证高并发下 Map->Bean 的转换只执行一次
            Object convertedValue = convertedCache.computeIfAbsent(cacheKey,
                    k -> Convert.convert(targetType, rawValue));
            // 透传 matchedCondition
            return RouteResult.matched((T) convertedValue, result.getMatchedCondition());
        }

        return (RouteResult<T>) result;
    }
}
