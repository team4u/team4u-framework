package com.team4u.framework.router.api.interceptor;

import com.team4u.framework.router.api.Router;
import com.team4u.framework.router.api.model.RouteResult;
import lombok.Getter;
import lombok.Setter;

import java.lang.reflect.Type;
import java.util.List;

/**
 * 默认的路由执行链实现
 *
 * @param <T> 路由结果类型
 */
@Getter
public class DefaultRouteInvocation<T> implements RouteInvocation<T> {
    private final String routerId;
    private final Router router;
    private final Class<T> targetType;
    private final Type targetGenericType;
    private final List<RouteInterceptor> interceptors;
    @Setter
    private Object request;
    private int currentIndex = 0;

    public DefaultRouteInvocation(String routerId,
                                  Router router,
                                  Object request,
                                  Type targetGenericType,
                                  List<RouteInterceptor> interceptors) {
        this.routerId = routerId;
        this.router = router;
        this.request = request;
        this.targetGenericType = targetGenericType;
        this.targetType = targetGenericType instanceof Class ? (Class<T>) targetGenericType : null;
        this.interceptors = interceptors;
    }

    @Override
    public RouteResult<T> proceed() {
        // 如果还有拦截器未执行，执行下一个拦截器
        if (currentIndex < interceptors.size()) {
            RouteInterceptor nextInterceptor = interceptors.get(currentIndex++);
            return nextInterceptor.intercept(this);
        }

        // 所有拦截器执行完毕，执行真正的目标路由逻辑
        if (router == null) {
            return RouteResult.unmatch();
        }

        return targetGenericType != null ? router.route(request, targetGenericType) : router.route(request);
    }
}
