package com.team4u.criterion.model.value;

import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.StrUtil;

import java.util.function.Function;

/**
 * Value 工厂类
 * <p>
 * 在解析 Token 时自动判断它是普通值还是变量：
 * <ul>
 * <li>带引号的字符串（'active'）：视为字符串常量，创建 FixedValue</li>
 * <li>纯数字（18）：视为数值常量，创建 FixedValue</li>
 * <li>无引号单词（minAge）：视为变量名，创建 VariableValue</li>
 * <li>特殊常量（null, true, false）：视为固定值，创建 FixedValue</li>
 * </ul>
 */
public class ValueFactory {

    /**
     * 创建值提供者
     *
     * @param rawToken   原始 Token（保留引号，例如 "'Jay'" 或 "minAge"）
     * @param typeParser 类型转换函数（用于静态值转换）
     * @param targetType 目标类型（用于动态变量转换）
     * @param <T>        值的类型
     * @return Value 对象
     */
    public static <T> Value<T> create(String rawToken, Function<String, T> typeParser, Class<T> targetType) {
        if (StrUtil.isBlank(rawToken)) {
            return new FixedValue<>(null);
        }

        // 1. 动态变量严格匹配 '$' 前缀
        if (rawToken.startsWith("$")) {
            // 截取 '$' 符号后的真实变量名
            String varName = rawToken.substring(1);
            if (StrUtil.isBlank(varName)) {
                throw new IllegalArgumentException("Invalid variable name: " + rawToken);
            }
            return new VariableValue<>(varName, targetType);
        }

        // --- 以下全部解析为静态常量 (FixedValue) ---

        // 2. 被引号包裹的字符串 (例如 'VIP')
        if (StrUtil.isWrap(rawToken, "'", "'")) {
            // 统一调用 parseString 进行去引号和反转义
            return new FixedValue<>(typeParser.apply(parseString(rawToken)));
        }

        // 3. 纯数字 (例如 18, 3.14)
        if (NumberUtil.isNumber(rawToken)) {
            return new FixedValue<>(typeParser.apply(rawToken));
        }

        // 4. 特殊关键字 (null, true, false)
        if ("null".equalsIgnoreCase(rawToken)) {
            return new FixedValue<>(null);
        }
        if ("true".equalsIgnoreCase(rawToken) || "false".equalsIgnoreCase(rawToken)) {
            return new FixedValue<>(typeParser.apply(rawToken.toLowerCase()));
        }

        // 5. 无引号字符串 (Unquoted String)
        return new FixedValue<>(typeParser.apply(rawToken));
    }

    /**
     * 解析字符串 Token
     * <p>
     * 1. 如果被单引号包裹，去除首尾引号
     * 2. 否则原样返回
     *
     * @param rawToken 原始 Token
     * @return 解析后的字符串
     */
    public static String parseString(String rawToken) {
        if (StrUtil.isWrap(rawToken, "'", "'")) {
            return rawToken.substring(1, rawToken.length() - 1);
        }
        return rawToken;
    }

    /**
     * 根据 token 内置自动推断为数值或字符串
     */
    public static Value<Object> createAuto(String rawToken) {
        if (NumberUtil.isNumber(rawToken)) {
            return create(rawToken, com.team4u.criterion.util.FastNumberUtil::toNumber, Object.class);
        }
        return create(rawToken, s -> s, Object.class);
    }
}