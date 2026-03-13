package com.team4u.framework.base.instance;

import com.team4u.framework.base.util.CacheUtil;
import com.team4u.framework.base.util.ReflectUtil;

/**
 * 单例工厂类
 * <p>
 * 基于 {@link DynamicInstanceProvider} 实现，通过类反射创建并缓存实例。
 * 适用于不需要复杂配置，仅通过类定义即可创建的单例对象。
 *
 * @author jay.wu
 */
public class SingletonFactory {

    /**
     * 内部动态实例提供者
     * Key: Class<?> (输入与配置均为类本身)
     * Value: Object (创建出的实例)
     */
    private static final DynamicInstanceProvider<Class<?>, Class<?>, Object> PROVIDER = new DynamicInstanceProvider<>(
            // 默认使用 LFU 缓存，容量 1000
            CacheUtil.newLFUCache(1000),
            // Input -> Config: 直接返回类本身
            clazz -> clazz,
            // Config -> Instance: 使用反射创建实例
            ReflectUtil::newInstance
    );

    /**
     * 获取指定类型的单例实例
     * <p>
     * 如果实例不存在，则通过反射创建并存入缓存。
     *
     * @param clazz 实例类型
     * @param <T>   泛型
     * @return 实例对象
     */
    @SuppressWarnings("unchecked")
    public static <T> T getInstance(Class<T> clazz) {
        return (T) PROVIDER.get(clazz);
    }

    /**
     * 移除特定类型的缓存
     *
     * @param clazz 实例类型
     */
    public static void invalidate(Class<?> clazz) {
        PROVIDER.invalidate(clazz);
    }

    /**
     * 清空所有单例缓存
     */
    public static void clear() {
        PROVIDER.clear();
    }
}
