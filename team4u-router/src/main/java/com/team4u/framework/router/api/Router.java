package com.team4u.framework.router.api;

import com.team4u.framework.base.util.TypeReference;
import com.team4u.framework.router.api.model.RouteResult;
import com.team4u.framework.router.api.trace.RouteTrace;

import java.lang.reflect.Type;

/**
 * 路由执行器核心接口
 * <p>
 * 路由器是路由的核心抽象，负责将输入的请求对象（Request）根据预设的策略映射到具体的目标标识或结果值。
 * 它可以支持简单的精确匹配、复杂的表达式判断、流量权重分配或多级嵌套组合。
 * </p>
 *
 * @author jay.wu
 */
public interface Router {

    /**
     * 执行路由逻辑
     *
     * @param request 路由请求对象，可以是一个 POJO、Map 或基本类型
     * @param <T>     预期的路由结果类型
     * @return 路由结果，包含匹配状态和路由值
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
    default <T> RouteResult<T> route(Object request, Class<T> targetType) {
        return route(request, (Type) targetType);
    }

    /**
     * 执行路由并转换结果类型
     *
     * @param request    路由请求对象
     * @param targetType 期望转换的目标类型
     * @param <T>        结果类型
     * @return 路由结果
     */
    <T> RouteResult<T> route(Object request, Type targetType);

    /**
     * 执行路由并转换结果类型
     *
     * @param request       路由请求对象
     * @param typeReference 期望转换的目标类型引用
     * @param <T>           结果类型
     * @return 路由结果
     */
    default <T> RouteResult<T> route(Object request, TypeReference<T> typeReference) {
        return route(request, typeReference != null ? typeReference.getType() : null);
    }

    /**
     * 返回路由执行过程的诊断轨迹
     * <p>
     * 在开发调试或问题排查阶段，通过诊断轨迹可以清晰了解路由匹配了哪些规则、耗时以及最终结果。
     * 默认实现直接调用 {@link #route(Object)} 并封装结果。
     * </p>
     *
     * @param request 路由请求对象
     * @param <T>     结果类型
     * @return 包含执行详情的诊断轨迹对象
     */
    default <T> RouteTrace<T> trace(Object request) {
        long start = System.currentTimeMillis();
        RouteTrace<T> trace = new RouteTrace<>();
        trace.setResult(route(request));
        trace.setCostMs(System.currentTimeMillis() - start);
        return trace;
    }
}
