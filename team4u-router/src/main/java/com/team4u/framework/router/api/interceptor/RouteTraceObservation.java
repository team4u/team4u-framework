package com.team4u.framework.router.api.interceptor;

import com.team4u.framework.router.api.Router;
import com.team4u.framework.router.api.trace.RouteTrace;
import lombok.Value;

/**
 * Trace 观察上下文。
 */
@Value
public class RouteTraceObservation<T> {
    String routerId;
    Router router;
    Object request;
    RouteTrace<T> trace;
}
