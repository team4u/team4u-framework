package com.team4u.framework.base.util;

import java.util.Collection;
import java.util.Map;

/**
 * 断言工具类
 * <p>
 * 用于对程序逻辑中的关键条件进行校验，若不符合预期则抛出 {@link IllegalArgumentException} 异常。
 *
 * @author jay.wu
 */
public class Assert {

    /**
     * 断言表达式为真
     * <p>
     * 校验给定的逻辑表达式，若结果为 false 则抛出异常。
     *
     * @param expression 逻辑表达式
     * @param message    校验失败时的异常消息
     * @throws IllegalArgumentException 当表达式为 false 时抛出
     */
    public static void isTrue(boolean expression, String message) {
        if (!expression) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * 断言对象不为 null
     * <p>
     * 校验给定的对象引用，若为 null 则抛出异常。
     *
     * @param object  待检查的对象
     * @param message 校验失败时的异常消息
     * @throws IllegalArgumentException 当对象为 null 时抛出
     */
    public static void notNull(Object object, String message) {
        if (object == null) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * 断言字符串不为空白
     * <p>
     * 校验字符串是否包含实际文本内容（即非 null、非空字符串且不全由空格组成）。
     *
     * @param text    待检查的字符串
     * @param message 校验失败时的异常消息
     * @throws IllegalArgumentException 当字符串为空白时抛出
     */
    public static void notBlank(String text, String message) {
        if (StringUtil.isBlank(text)) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * 断言集合不为空
     * <p>
     * 校验集合对象，若为 null 或不包含任何元素则抛出异常。
     *
     * @param collection 待检查的集合
     * @param message    校验失败时的异常消息
     * @throws IllegalArgumentException 当集合为空时抛出
     */
    public static void notEmpty(Collection<?> collection, String message) {
        if (CollectionUtil.isEmpty(collection)) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * 断言 Map 不为空
     * <p>
     * 校验 Map 对象，若为 null 或不包含任何键值对则抛出异常。
     *
     * @param map     待检查的 Map
     * @param message 校验失败时的异常消息
     * @throws IllegalArgumentException 当 Map 为空时抛出
     */
    public static void notEmpty(Map<?, ?> map, String message) {
        if (CollectionUtil.isEmpty(map)) {
            throw new IllegalArgumentException(message);
        }
    }
}
