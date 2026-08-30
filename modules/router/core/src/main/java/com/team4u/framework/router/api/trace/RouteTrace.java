package com.team4u.framework.router.api.trace;

import com.team4u.framework.router.api.model.RouteResult;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 路由执行诊断轨迹
 *
 * @param <T> 路由结果类型
 */
@Data
public class RouteTrace<T> {

    /**
     * 最终的路由结果
     */
    private RouteResult<T> result;

    /**
     * 路由器的类型 (如 expression, map 等)
     */
    private String routerType;

    /**
     * 规则执行的步骤轨迹
     */
    private List<RuleTrace> steps = new ArrayList<>();

    /**
     * 额外的观察事件
     */
    private List<RouteTraceEvent> events = new ArrayList<>();

    /**
     * 耗时 (毫秒)
     */
    private long costMs;

    /**
     * 添加执行步骤
     *
     * @param step 执行步骤
     */
    public void addStep(RuleTrace step) {
        this.steps.add(step);
    }

    public void addEvent(RouteTraceEvent event) {
        this.events.add(event);
    }
}
