package com.team4u.framework.router.api.builder;

import com.team4u.framework.router.api.RouterType;
import com.team4u.framework.router.api.exception.RouteConfigException;

/**
 * 路由策略构建器外观类
 * <p>
 * 提供快捷的静态工厂方法来创建特定类型的路由构建器。
 * 这种模式有助于 IDE 提供更精准的 API 提示，并防止跨类型的配置错误。
 * </p>
 *
 * @author jay.wu
 */
public class RoutePolicyBuilder {

    /**
     * 创建一个 Map (映射) 类型的路由策略构建器
     *
     * @param <T> 路由结果值的类型
     * @return 映射路由构建器
     */
    public static <T> RuleRoutePolicyBuilder<T> map() {
        return new RuleRoutePolicyBuilder<>(RouterType.MAP);
    }

    /**
     * 创建一个 Expression (表达式) 类型的路由策略构建器
     *
     * @param <T> 路由结果值的类型
     * @return 表达式路由构建器
     */
    public static <T> RuleRoutePolicyBuilder<T> expression() {
        return new RuleRoutePolicyBuilder<>(RouterType.EXPRESSION);
    }

    /**
     * 创建一个 Weight (权重) 类型的路由策略构建器
     *
     * @param <T> 路由结果值的类型
     * @return 权重路由构建器
     */
    public static <T> RuleRoutePolicyBuilder<T> weight() {
        return new RuleRoutePolicyBuilder<>(RouterType.WEIGHT);
    }

    /**
     * 创建一个 Composite (组合) 类型的路由策略构建器
     *
     * @param <T> 路由结果值的类型
     * @return 组合路由构建器
     */
    public static <T> CompositeRoutePolicyBuilder<T> composite() {
        return new CompositeRoutePolicyBuilder<>();
    }

    /**
     * 创建一个自定义类型的路由策略构建器
     *
     * @param type 路由器类型标识
     * @param <T>  路由结果值的类型
     * @return 自定义路由构建器 (默认提供规则支持)
     */
    public static <T> RuleRoutePolicyBuilder<T> custom(String type) {
        if (type == null || type.trim().isEmpty()) {
            throw RouteConfigException.validationError("Router type cannot be empty");
        }
        return new RuleRoutePolicyBuilder<>(type);
    }
}
