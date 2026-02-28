package com.team4u.framework.router.parser;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.team4u.framework.router.api.exception.RouteConfigException;
import com.team4u.framework.router.api.model.RoutePolicy;
import com.team4u.framework.router.spi.RoutePolicyParser;

import java.util.ArrayList;

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

        RoutePolicy policy;
        try {
            policy = JSONUtil.toBean(input, RoutePolicy.class);
        } catch (Exception e) {
            throw RouteConfigException.parseError("Failed to parse JSON to RoutePolicy", e);
        }

        if (policy != null) {
            // 校验：策略类型不能为空
            if (StrUtil.isBlank(policy.getType())) {
                throw RouteConfigException.validationError("RoutePolicy type cannot be null or empty.");
            }

            // 防御性校验：确保 rules 不为 null
            if (policy.getRules() == null) {
                policy.setRules(new ArrayList<>());
            }
        }

        return policy;
    }
}
