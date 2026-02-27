package com.team4u.framework.router.api;

import cn.hutool.core.convert.Convert;

/**
 * 抽象路由器，处理通用的类型转换和本地缓存逻辑
 *
 * @author jay.wu
 */
public abstract class AbstractRouter implements Router {

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
            // 直接进行类型转换，不再使用本地缓存
            T convertedValue = Convert.convert(targetType, rawValue);
            // 透传 matchedCondition
            return RouteResult.matched(convertedValue, result.getMatchedCondition());
        }

        return (RouteResult<T>) result;
    }
}
