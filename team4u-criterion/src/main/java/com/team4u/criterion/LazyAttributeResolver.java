package com.team4u.criterion;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 延迟加载属性解析器
 * <p>
 * 用于在 MatchContext 中注册多个 Key 对应的延迟加载逻辑。
 * 开发者可以通过链式调用 register 方法来注册不同的属性提供者。
 *
 * @author jay.wu
 */
public class LazyAttributeResolver implements AttributeResolver {
    /**
     * 各个 Key 对应的解析器映射表（值为 AttributeResolver）
     */
    private final Map<String, AttributeResolver> providers = new HashMap<>();

    /**
     * 针对指定 Key 注册一个延迟加载逻辑（使用 Supplier 简便写法，忽略上下文）
     *
     * @param key      属性 Key
     * @param supplier 属性值的提供者，不需要感知上下文时使用
     * @return 当前解析器对象，支持链式调用
     */
    public LazyAttributeResolver register(String key, Supplier<Object> supplier) {
        providers.put(key, (context, k) -> supplier.get());
        return this;
    }

    /**
     * 针对指定 Key 注册一个延迟加载逻辑（可感知上下文）
     *
     * @param key      属性 Key
     * @param resolver 属性解析器，可读取上下文中的数据
     * @return 当前解析器对象，支持链式调用
     */
    public LazyAttributeResolver register(String key, AttributeResolver resolver) {
        providers.put(key, resolver);
        return this;
    }

    @Override
    public Object resolve(MatchContext context, String key) {
        // 自动路由到对应的解析器并获取值
        AttributeResolver resolver = providers.get(key);
        return resolver != null ? resolver.resolve(context, key) : null;
    }
}