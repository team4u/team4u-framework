package com.team4u.framework.serializer.json;

import com.team4u.framework.policy.api.ContextPolicy;
import com.team4u.framework.policy.api.KeyedPolicy;

import java.lang.reflect.Type;
import java.util.List;

/**
 * JSON 序列化策略接口
 *
 * @author jay.wu
 */
public interface JsonSerializerPolicy extends ContextPolicy<Void>, KeyedPolicy<String> {

    /**
     * 将对象转换为 JSON 字符串
     *
     * @param obj 对象
     * @return JSON 字符串
     */
    String toJsonStr(Object obj);

    /**
     * 将 JSON 字符串转换为对象
     *
     * @param json  JSON 字符串
     * @param clazz 对象类型
     * @param <T>   类型
     * @return 对象
     */
    <T> T toBean(String json, Class<T> clazz);

    /**
     * 将 JSON 字符串转换为复杂泛型对象
     *
     * @param json JSON 字符串
     * @param type 类型
     * @param <T>  类型
     * @return 对象
     */
    <T> T toBean(String json, Type type);

    /**
     * 将 JSON 字符串转换为 List
     *
     * @param json  JSON 字符串
     * @param clazz 元素类型
     * @param <T>   类型
     * @return List
     */
    <T> List<T> toList(String json, Class<T> clazz);

    /**
     * 解析为通用对象
     *
     * @param json JSON 字符串
     * @return 对象
     */
    Object parseObj(String json);
}
