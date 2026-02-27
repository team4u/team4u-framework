package com.team4u.framework.router.spi;

import com.team4u.framework.policy.api.KeyedPolicy;
import com.team4u.framework.router.api.Router;
import com.team4u.framework.router.api.model.RoutePolicy;

/**
 * 路由工厂
 */
public interface RouterFactory extends KeyedPolicy<String> {

    /**
     * 创建路由器
     *
     * @param policy 路由配置
     * @return 路由器实例
     */
    Router create(RoutePolicy policy);
}
