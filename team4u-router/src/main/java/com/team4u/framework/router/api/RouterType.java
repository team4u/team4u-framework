package com.team4u.framework.router.api;

/**
 * 路由类型常量定义
 * <p>
 * 提供标准路由类型的常量定义，避免在代码中使用魔法字符串。
 * </p>
 *
 * @author jay.wu
 */
public final class RouterType {

    /**
     * 映射路由类型
     * <p>
     * 基于精确匹配的路由策略，适用于固定键值映射场景。
     * </p>
     */
    public static final String MAP = "map";
    /**
     * 表达式路由类型
     * <p>
     * 基于条件表达式的路由策略，支持复杂的条件判断逻辑。
     * </p>
     */
    public static final String EXPRESSION = "expression";
    /**
     * 权重路由类型
     * <p>
     * 基于权重的路由策略，支持按比例流量分发。
     * </p>
     */
    public static final String WEIGHT = "weight";
    /**
     * 组合路由类型
     * <p>
     * 具备分发联动属性的组合路由组件，瀑布式执行内部多个子路由。
     * </p>
     */
    public static final String COMPOSITE = "composite";

    private RouterType() {
        // 防止实例化
    }

    /**
     * 验证路由类型是否有效
     *
     * @param type 路由类型
     * @return 如果是标准类型返回 true
     */
    public static boolean isValid(String type) {
        return MAP.equals(type) || EXPRESSION.equals(type) || WEIGHT.equals(type) || COMPOSITE.equals(type);
    }
}
