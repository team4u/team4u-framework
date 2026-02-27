package com.team4u.framework.router.api.interceptor;

import com.team4u.framework.router.api.Router;
import com.team4u.framework.router.api.model.RouteResult;

import java.util.List;

/**
 * 默认的路由执行链实现
 *
 * @param <T> 路由结果类型
 */
public class DefaultRouteInvocation<T> implements RouteInvocation<T> {
    private final String routerId;
    private final Router targetRouter;
    private final Class<T> targetType;
    private final List<RouteInterceptor> interceptors;
    private Object request;
    private int currentIndex = 0;

    public DefaultRouteInvocation(String routerId,
                                  Router targetRouter,
                                  Object request,
                                  Class<T> targetType,
                                  List<RouteInterceptor> interceptors) {
        this.routerId = routerId;
        this.targetRouter = targetRouter;
        this.request = request;
        this.targetType = targetType;
        this.interceptors = interceptors;
    }

    @Override
    public String getRouterId() {
        return routerId;
    }

    @Override
    public Object getRequest() {
        return request;
    }

    @Override
    public void setRequest(Object request) {
        this.request = request;
    }

    @Override
    public Class<T> getTargetType() {
        return targetType;
    }

    @Override
    public RouteResult<T> proceed() {
        // 如果还有拦截器未执行，执行下一个拦截器
        if (currentIndex < interceptors.size()) {
            RouteInterceptor nextInterceptor = interceptors.get(currentIndex++);
            return nextInterceptor.intercept(this);
        }

        // 所有拦截器执行完毕，执行真正的目标路由逻辑
        if (targetRouter == null) {
            return RouteResult.unmatch();
        }

        return targetType != null ? targetRouter.route(request, targetType) : targetRouter.route(request);
    }
}
