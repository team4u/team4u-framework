package com.team4u.framework.router.api.trace;

import lombok.Value;

/**
 * Trace 附加事件。
 */
@Value
public class RouteTraceEvent {
    String source;
    String phase;
    Object detail;
}
