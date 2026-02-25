package com.team4u.framework.criterion;

/**
 * 属性解析器接口
 * <p>
 * 用于在 MatchContext 中动态解析属性值。
 *
 * @author jay.wu
 */
@FunctionalInterface
public interface AttributeResolver {

    /**
     * 解析属性值
     *
     * @param context 匹配上下文
     * @param key     属性 Key
     * @return 解析出的属性值，如果不存在则返回 null
     */
    Object resolve(MatchContext context, String key);
}
