package com.team4u.framework.router.parser;

import com.team4u.framework.base.util.StringUtil;
import com.team4u.framework.router.api.exception.RouteConfigException;
import com.team4u.framework.router.api.model.RoutePolicy;
import com.team4u.framework.router.spi.RoutePolicyParser;
import com.team4u.framework.serializer.json.JsonUtil;

import java.util.ArrayList;

/**
 * 默认路由策略解析器 (Default Route Policy Parser)
 * <p>
 * 基于 {@link com.team4u.framework.serializer.json.JsonUtil} 实现的 JSON 解析器。
 * 它能够将配置中心的 JSON 字符串反序列化为 {@link RoutePolicy} 模型，并进行基础的合规性校验。
 * </p>
 *
 * @author n197
 */
public class DefaultRoutePolicyParser implements RoutePolicyParser {

    @Override
    public RoutePolicy parse(String input) {
        if (StringUtil.isBlank(input)) {
            return null;
        }

        RoutePolicy policy;
        try {
            policy = JsonUtil.toBean(input, RoutePolicy.class);
        } catch (Exception e) {
            throw RouteConfigException.parseError("Failed to parse JSON to RoutePolicy", e);
        }

        if (policy != null) {
            // 校验：策略类型不能为空
            if (StringUtil.isBlank(policy.getType())) {
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
