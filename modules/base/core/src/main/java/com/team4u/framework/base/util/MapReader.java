package com.team4u.framework.base.util;

import com.team4u.framework.base.convert.ConvertUtil;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;

/**
 * 字典强类型读取器（Map Reader）。
 * <p>
 * 基于 {@link ConvertUtil} 提供强类型安全的参数提取能力，
 * 统一支持 Key 别名解析（如 camelCase 与 kebab-case 兼容）和安全默认值回退，消除弱类型 Map 手工校验与类型转换样板代码。
 *
 * @author jay.wu
 */
public class MapReader {

    private final Map<?, ?> map;

    /**
     * 构造 MapReader 实例。
     *
     * @param map 原始字典（允许为 null）
     */
    public MapReader(Map<?, ?> map) {
        this.map = map != null ? map : Collections.emptyMap();
    }

    /**
     * 静态工厂方法构造 MapReader 实例。
     *
     * @param map 原始字典（允许为 null）
     * @return MapReader 实例
     */
    public static MapReader of(Map<?, ?> map) {
        return new MapReader(map);
    }

    /**
     * 按主键及可选别名依次查找首个非 null 原始值。
     *
     * @param key     主键
     * @param aliases 可选别名
     * @return 首个非 null 值，若均未找到则返回 null
     */
    public Object getRaw(String key, String... aliases) {
        Object val = map.get(key);
        if (val != null) {
            return val;
        }
        if (aliases != null) {
            for (String alias : aliases) {
                if (alias != null) {
                    val = map.get(alias);
                    if (val != null) {
                        return val;
                    }
                }
            }
        }
        return null;
    }

    /**
     * 读取必填原始参数，若缺失则抛出 {@link IllegalArgumentException}。
     *
     * @param key          主键
     * @param errorMessage 错误消息
     * @param aliases      可选别名
     * @return 原始非 null 对象
     */
    public Object require(String key, String errorMessage, String... aliases) {
        Object val = getRaw(key, aliases);
        if (val == null) {
            throw new IllegalArgumentException(errorMessage != null ? errorMessage : "Missing required configuration: " + key);
        }
        return val;
    }

    /**
     * 读取必填字符串参数，若缺失则抛出 {@link IllegalArgumentException}。
     *
     * @param key          主键
     * @param errorMessage 错误消息
     * @param aliases      可选别名
     * @return 字符串值
     */
    public String requireString(String key, String errorMessage, String... aliases) {
        return ConvertUtil.toStr(require(key, errorMessage, aliases));
    }

    /**
     * 检查字典中是否包含指定主键或任一别名。
     *
     * @param key     主键
     * @param aliases 可选别名
     * @return 若包含则返回 true，否则返回 false
     */
    public boolean containsKey(String key, String... aliases) {
        if (map.containsKey(key)) {
            return true;
        }
        if (aliases != null) {
            for (String alias : aliases) {
                if (alias != null && map.containsKey(alias)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 读取字符串参数（支持别名与默认值）。
     *
     * @param key          主键
     * @param defaultValue 默认值
     * @param aliases      可选别名
     * @return 字符串值
     */
    public String getString(String key, String defaultValue, String... aliases) {
        Object val = getRaw(key, aliases);
        return val != null ? ConvertUtil.toStr(val, defaultValue) : defaultValue;
    }

    /**
     * 读取字符串参数。
     *
     * @param key 主键
     * @return 字符串值（若未找到返回 null）
     */
    public String getString(String key) {
        return getString(key, null);
    }

    /**
     * 读取整型参数（支持别名与默认值）。
     *
     * @param key          主键
     * @param defaultValue 默认值
     * @param aliases      可选别名
     * @return 整型值
     */
    public Integer getInt(String key, Integer defaultValue, String... aliases) {
        Object val = getRaw(key, aliases);
        return val != null ? ConvertUtil.toInt(val, defaultValue) : defaultValue;
    }

    /**
     * 读取整型参数。
     *
     * @param key 主键
     * @return 整型值（若未找到返回 null）
     */
    public Integer getInt(String key) {
        return getInt(key, null);
    }

    /**
     * 读取长整型参数（支持别名与默认值）。
     *
     * @param key          主键
     * @param defaultValue 默认值
     * @param aliases      可选别名
     * @return 长整型值
     */
    public Long getLong(String key, Long defaultValue, String... aliases) {
        Object val = getRaw(key, aliases);
        return val != null ? ConvertUtil.toLong(val, defaultValue) : defaultValue;
    }

    /**
     * 读取长整型参数。
     *
     * @param key 主键
     * @return 长整型值（若未找到返回 null）
     */
    public Long getLong(String key) {
        return getLong(key, null);
    }

    /**
     * 读取双精度浮点数参数（支持别名与默认值）。
     *
     * @param key          主键
     * @param defaultValue 默认值
     * @param aliases      可选别名
     * @return 浮点数值
     */
    public Double getDouble(String key, Double defaultValue, String... aliases) {
        Object val = getRaw(key, aliases);
        return val != null ? ConvertUtil.toDouble(val, defaultValue) : defaultValue;
    }

    /**
     * 读取双精度浮点数参数。
     *
     * @param key 主键
     * @return 浮点数值（若未找到返回 null）
     */
    public Double getDouble(String key) {
        return getDouble(key, null);
    }

    /**
     * 读取布尔参数（支持别名与默认值）。
     *
     * @param key          主键
     * @param defaultValue 默认值
     * @param aliases      可选别名
     * @return 布尔值
     */
    public Boolean getBoolean(String key, Boolean defaultValue, String... aliases) {
        Object val = getRaw(key, aliases);
        return val != null ? ConvertUtil.toBool(val, defaultValue) : defaultValue;
    }

    /**
     * 读取布尔参数。
     *
     * @param key 主键
     * @return 布尔值（若未找到返回 null）
     */
    public Boolean getBoolean(String key) {
        return getBoolean(key, null);
    }

    /**
     * 读取时长参数（支持别名、数值毫秒、文本格式及默认值）。
     *
     * @param key          主键
     * @param defaultValue 默认值
     * @param aliases      可选别名
     * @return 时长 Duration 实例
     */
    public Duration getDuration(String key, Duration defaultValue, String... aliases) {
        Object val = getRaw(key, aliases);
        return val != null ? ConvertUtil.toDuration(val, defaultValue) : defaultValue;
    }

    /**
     * 读取时长参数。
     *
     * @param key 主键
     * @return 时长 Duration 实例（若未找到返回 null）
     */
    public Duration getDuration(String key) {
        return getDuration(key, null);
    }

    /**
     * 读取枚举参数（支持别名、大小写不敏感与默认值）。
     *
     * @param enumClass    枚举 Class
     * @param key          主键
     * @param defaultValue 默认值
     * @param aliases      可选别名
     * @param <E>          枚举泛型
     * @return 枚举常量值
     */
    public <E extends Enum<E>> E getEnum(Class<E> enumClass, String key, E defaultValue, String... aliases) {
        Object val = getRaw(key, aliases);
        if (val == null) {
            return defaultValue;
        }
        return ConvertUtil.convert(enumClass, val, defaultValue);
    }

    /**
     * 读取枚举参数。
     *
     * @param enumClass 枚举 Class
     * @param key       主键
     * @param <E>       枚举泛型
     * @return 枚举常量值（若未找到返回 null）
     */
    public <E extends Enum<E>> E getEnum(Class<E> enumClass, String key) {
        return getEnum(enumClass, key, null);
    }

    /**
     * 通用强类型参数转换读取（支持别名与默认值）。
     *
     * @param type         目标 Class 类型
     * @param key          主键
     * @param defaultValue 默认值
     * @param aliases      可选别名
     * @param <T>          目标泛型
     * @return 转换后的值
     */
    public <T> T get(Class<T> type, String key, T defaultValue, String... aliases) {
        Object val = getRaw(key, aliases);
        if (val == null) {
            return defaultValue;
        }
        return ConvertUtil.convert(type, val, defaultValue);
    }

    /**
     * 通用强类型参数转换读取。
     *
     * @param type 目标 Class 类型
     * @param key  主键
     * @param <T>  目标泛型
     * @return 转换后的值（若未找到返回 null）
     */
    public <T> T get(Class<T> type, String key) {
        return get(type, key, null);
    }
}
