package com.team4u.framework.singleflight.proxy;

import com.team4u.framework.proxy.ProxyBuilder;

/**
 * 回源合并代理的便捷工厂（非 Spring 场景的编程式入口）：以
 * {@link SingleFlightInterceptor} 包装目标对象，未注解方法自动直通。
 * <p>
 * Spring 场景经 {@code @EnableSingleFlight} 的 BeanPostProcessor 自动装配；
 * 本工厂仅保留直接编程创建代理的便捷方法，构建逻辑全部委托 {@link ProxyBuilder}。
 * 提供两组形态——同接口代理（目标本身即实现类型）与指定接口代理（委托对象 + 接口类型），
 * 各自均可选配 {@link SingleFlightExceptionHandler} 做组件异常转换。
 * </p>
 *
 * @author jay.wu
 */
public final class SingleFlightProxyFactory {

    private SingleFlightProxyFactory() {
    }

    /**
     * 以目标对象的运行时类型创建代理。
     */
    public static <T> T proxy(T target) {
        return ProxyBuilder.proxy(target, new SingleFlightInterceptor());
    }

    /**
     * 以目标对象的运行时类型创建代理，并配置组件异常处理器。
     */
    public static <T> T proxy(T target, SingleFlightExceptionHandler exceptionHandler) {
        return ProxyBuilder.proxy(target, new SingleFlightInterceptor(exceptionHandler));
    }

    /**
     * 以指定接口创建委托代理。
     */
    public static <T> T proxy(Object target, Class<T> targetInterface) {
        return ProxyBuilder.forClass(targetInterface)
                .delegate(target)
                .intercept(new SingleFlightInterceptor())
                .build();
    }

    /**
     * 以指定接口创建委托代理，并配置组件异常处理器。
     */
    public static <T> T proxy(Object target, Class<T> targetInterface,
                              SingleFlightExceptionHandler exceptionHandler) {
        return ProxyBuilder.forClass(targetInterface)
                .delegate(target)
                .intercept(new SingleFlightInterceptor(exceptionHandler))
                .build();
    }
}
