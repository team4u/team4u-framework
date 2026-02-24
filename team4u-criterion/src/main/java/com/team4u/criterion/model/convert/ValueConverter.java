package com.team4u.criterion.model.convert;

import com.team4u.policy.KeyedPolicy;

import java.util.function.Function;

/**
 * 值转换器接口
 * <p>
 * 将任意对象转换为可比较对象 (Comparable)
 *
 * @author jay.wu
 */
public interface ValueConverter extends Function<Object, Comparable<?>>, KeyedPolicy<String> {
    /**
     * 获取转换器标识 (如: "date", "version")
     */
    @Override
    default String key() {
        return id();
    }

    /**
     * 获取转换器标识
     */
    String id();
}
