package com.team4u.framework.router.api;

import lombok.Data;

/**
 * 路由结果
 *
 * @param <T> 结果类型
 */
@Data
public class RouteResult<T> {

    private final boolean match;
    private final T value;

    private RouteResult(boolean match, T value) {
        this.match = match;
        this.value = value;
    }

    /**
     * 匹配成功
     *
     * @param value 匹配值
     * @param <T>   结果类型
     * @return 路由结果
     */
    public static <T> RouteResult<T> matched(T value) {
        return new RouteResult<>(true, value);
    }

    /**
     * 匹配失败
     *
     * @param <T> 结果类型
     * @return 路由结果
     */
    public static <T> RouteResult<T> unmatch() {
        return new RouteResult<>(false, null);
    }

    public boolean isNotMatch() {
        return !match;
    }
}
