package com.team4u.framework.router.parser;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.team4u.framework.router.api.RoutePolicy;
import com.team4u.framework.router.api.RoutePolicyParser;

/**
 * 默认的路由策略解析器 (基于 Hutool JSON)
 *
 * @author n197
 */
public class DefaultRoutePolicyParser implements RoutePolicyParser {

    @Override
    public RoutePolicy parse(String input) {
        if (StrUtil.isBlank(input)) {
            return null;
        }

        RoutePolicy policy = JSONUtil.toBean(input, RoutePolicy.class);

        // 校验：策略类型不能为空
        if (policy != null && StrUtil.isBlank(policy.getType())) {
            throw new IllegalArgumentException("RoutePolicy type cannot be null or empty.");
        }

        return policy;
    }
}
