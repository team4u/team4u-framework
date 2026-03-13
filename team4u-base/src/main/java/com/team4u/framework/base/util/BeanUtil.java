package com.team4u.framework.base.util;

import com.team4u.framework.base.convert.ConvertUtil;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bean 工具类
 * <p>
 * 提供 Java Bean 与 Map 之间的转换、属性获取及字段搜索等功能。支持级联属性访问、自动类型转换以及基于规则的字段匹配。
 *
 * @author jay.wu
 */
public class BeanUtil {

    /**
     * 规范化字段缓存，用于提高忽略大小写及特殊字符匹配的性能
     */
    private static final Map<Class<?>, Map<String, Field>> NORMALIZED_FIELD_CACHE = new ConcurrentHashMap<>();

    /**
     * 将 Map 转换为 Bean 对象
     * <p>
     * 根据 Map 中的键值对填充目标类的实例属性。支持递归处理嵌套的 Map 到 Bean 的转换，并利用 {@link ConvertUtil}
     * 进行自动类型转换。
     *
     * @param map     包含数据的 Map 对象
     * @param clazz   目标 Bean 的类类型
     * @param options 复制选项，用于控制忽略大小写、忽略错误等行为
     * @param <T>     目标 Bean 的泛型类型
     * @return 填充后的 Bean 实例，若输入为空或实例化失败则返回 null
     */
    public static <T> T toBean(Map<?, ?> map, Class<T> clazz, CopyOptions options) {
        if (map == null || clazz == null) {
            return null;
        }

        T bean = ReflectUtil.newInstance(clazz);
        if (bean == null) {
            return null;
        }

        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            if (value == null) {
                continue;
            }

            try {
                Field field = findField(clazz, key, options.isIgnoreCase());
                if (field != null) {
                    Object convertedValue;
                    // 如果字段是一个自定义 Bean 且值是一个 Map，则进行递归绑定
                    if (value instanceof Map && !Map.class.isAssignableFrom(field.getType())
                            && !isSimpleType(field.getType())) {
                        convertedValue = toBean((Map<?, ?>) value, field.getType(), options);
                    } else {
                        // 传入 getGenericType 以保留泛型信息，支持如 List<Integer> 的转换
                        convertedValue = ConvertUtil.convert(field.getGenericType(), value);
                    }
                    field.set(bean, convertedValue);
                }
            } catch (Exception e) {
                if (!options.isIgnoreError()) {
                    throw new RuntimeException("Set field error: " + key, e);
                }
            }
        }
        return bean;
    }

    /**
     * 判断是否为简单数据类型
     * <p>
     * 简单类型包括基本类型、String、包装类（Number 子类、Boolean、Character）。
     */
    private static boolean isSimpleType(Class<?> type) {
        return type.isPrimitive() || type == String.class || Number.class.isAssignableFrom(type)
                || type == Boolean.class || type == Character.class;
    }

    /**
     * 查找类及其父类中定义的指定名称的字段
     *
     * @param clazz      目标类
     * @param name       字段名称
     * @param ignoreCase 是否忽略名称中的大小写及特殊符号（如 - 和 _）
     * @return 匹配的字段对象，未找到则返回 null
     */
    private static Field findField(Class<?> clazz, String name, boolean ignoreCase) {
        if (!ignoreCase) {
            return ReflectUtil.getField(clazz, name);
        }

        // 获取规范化后的字段缓存
        Map<String, Field> fieldMap = NORMALIZED_FIELD_CACHE.computeIfAbsent(clazz, c -> {
            Map<String, Field> map = new HashMap<>();
            Class<?> searchType = c;
            while (searchType != null && searchType != Object.class) {
                for (Field field : searchType.getDeclaredFields()) {
                    ReflectUtil.makeAccessible(field);
                    // 预处理规范化名称，作为缓存的键
                    String normalizedName = normalize(field.getName());
                    map.putIfAbsent(normalizedName, field);
                }
                searchType = searchType.getSuperclass();
            }
            return map;
        });

        return fieldMap.get(normalize(name));
    }

    /**
     * 规范化字段名称
     * <p>
     * 将名称转为小写并移除横线与下划线，用于模糊匹配。
     */
    private static String normalize(String name) {
        if (name == null) {
            return null;
        }
        return name.toLowerCase().replace("-", "").replace("_", "");
    }

    /**
     * 获取对象的属性值
     * <p>
     * 支持从 Bean 或 Map 中提取指定名称的属性。支持点号分隔的级联属性（如 "user.address.city"）。
     *
     * @param bean         目标对象（Bean 实例或 Map）
     * @param propertyName 属性名称或级联属性路径
     * @return 属性值，若对象为空、属性名为空或属性不存在则返回 null
     */
    public static Object getProperty(Object bean, String propertyName) {
        if (bean == null || propertyName == null) {
            return null;
        }

        // 优先处理级联属性 address.city，支持嵌套 Map 或 Bean
        if (propertyName.contains(".")) {
            int dotIndex = propertyName.indexOf('.');
            String firstPart = propertyName.substring(0, dotIndex);
            String restPart = propertyName.substring(dotIndex + 1);
            Object value = getProperty(bean, firstPart);
            return getProperty(value, restPart);
        }

        if (bean instanceof Map) {
            return ((Map<?, ?>) bean).get(propertyName);
        }

        try {
            Field field = findField(bean.getClass(), propertyName, false);
            if (field != null) {
                return field.get(bean);
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
