package com.team4u.framework.router.api;

/**
 * 核心路由接口
 */
public interface Router {

    /**
     * 执行路由
     *
     * @param request 路由请求对象
     * @param <T>     结果类型
     * @return 路由结果
     */
    <T> RouteResult<T> route(Object request);

    /**
     * 执行路由并转换结果类型
     *
     * @param request    路由请求对象
     * @param targetType 期望转换的目标类型
     * @param <T>        结果类型
     * @return 路由结果
     */
    @SuppressWarnings("unchecked")
    default <T> RouteResult<T> route(Object request, Class<T> targetType) {
        RouteResult<?> result = route(request);
        if (result == null || result.isNotMatch()) {
            return RouteResult.unmatch();
        }
        
        Object value = result.getValue();
        if (value == null) {
            return RouteResult.matched(null);
        }
        
        if (targetType != null && !targetType.isInstance(value)) {
            T convertedValue = cn.hutool.core.convert.Convert.convert(targetType, value);
            return RouteResult.matched(convertedValue);
        }
        
        return (RouteResult<T>) result;
    }
}
