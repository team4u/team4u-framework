package com.team4u.framework.router.spi;

import com.team4u.framework.router.api.model.RoutePolicy;

import com.team4u.framework.base.config.StringConfigParser;

/**
 * 路由策略解析器
 * <p>
 * 负责将字符串配置（如 JSON/YAML）解析为 {@link RoutePolicy} 实例。
 * 业务方可通过实现此接口并注册，来替换默认的 JSON 解析引擎。
 * </p>
 *
 * @author n197
 */
@FunctionalInterface
public interface RoutePolicyParser extends StringConfigParser<RoutePolicy> {
}
