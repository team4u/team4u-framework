package com.team4u.framework.router.parser;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import java.util.ArrayList;
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

        if (policy != null) {
            // 校验：策略类型不能为空
            if (StrUtil.isBlank(policy.getType())) {
                throw new IllegalArgumentException("RoutePolicy type cannot be null or empty.");
            }

            // 防御性校验：确保 rules 不为 null
            if (policy.getRules() == null) {
                policy.setRules(new ArrayList<>());
            }
        }

        return policy;
    }
}
