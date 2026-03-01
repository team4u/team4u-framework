package com.team4u.framework.router.api.builder;

import com.team4u.framework.router.api.RouterType;
import com.team4u.framework.router.api.model.RoutePolicy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 组合型路由策略构建器
 * <p>
 * 提供特定于组合路由的方法，特别是接受可变参数的 delegates 列表。
 * </p>
 *
 * @param <T> 路由结果值的类型
 * @author jay.wu
 */
public class CompositeRoutePolicyBuilder<T> extends AbstractRoutePolicyBuilder<T, CompositeRoutePolicyBuilder<T>> {

    private final List<String> delegates = new ArrayList<>();

    public CompositeRoutePolicyBuilder() {
        super(RouterType.COMPOSITE);
    }

    /**
     * 添加委托路由 ID (支持可变参数)
     *
     * @param delegates 委托路由 ID 列表
     * @return 当前构建器实例
     */
    public CompositeRoutePolicyBuilder<T> delegates(String... delegates) {
        this.delegates.addAll(Arrays.asList(delegates));
        return this;
    }

    /**
     * 添加委托路由 ID 列表
     *
     * @param delegates 委托路由 ID 列表
     * @return 当前构建器实例
     */
    public CompositeRoutePolicyBuilder<T> delegates(List<String> delegates) {
        if (delegates != null) {
            this.delegates.addAll(delegates);
        }
        return this;
    }

    @Override
    protected void doBuild(RoutePolicy policy) {
        policy.getExt().put("delegates", new ArrayList<>(this.delegates));
    }
}
