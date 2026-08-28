package com.team4u.framework.serializer.json;

import com.team4u.framework.base.util.TypeReference;
import com.team4u.framework.policy.core.OrderedPolicyChain;
import com.team4u.framework.policy.util.PolicyScanner;

import java.lang.reflect.Type;
import java.util.List;

/**
 * JSON 工具类（核心门面）
 * <p>
 * 该类不直接持有特定的 JSON 框架，而是通过策略机制在运行时发现并选择合适的底层实现。
 * 默认情况下，它会自动扫描类路径下的 {@link JsonSerializerPolicy} 实现。
 *
 * @author jay.wu
 */
public class JsonUtil {

    private static final OrderedPolicyChain<Void, JsonSerializerPolicy> CHAIN =
            new OrderedPolicyChain<>(JsonSerializerPolicy.class);

    private static JsonSerializerPolicy defaultPolicy;

    static {
        // 自动发现并注册所有策略
        PolicyScanner.registerFromServiceLoader(CHAIN);
        PolicyScanner.scanAndRegister(CHAIN);

        // 选择优先级最高且支持当前环境的策略
        defaultPolicy = CHAIN.firstMatch(null).orElse(null);
    }

    /**
     * 将对象转换为 JSON 字符串
     *
     * @param obj 对象
     * @return JSON 字符串
     */
    public static String toJsonStr(Object obj) {
        if (obj == null) {
            return null;
        }
        return getPolicy().toJsonStr(obj);
    }

    /**
     * 将 JSON 字符串转换为对象
     *
     * @param json  JSON 字符串
     * @param clazz 对象类型
     * @param <T>   类型
     * @return 对象
     */
    public static <T> T toBean(String json, Class<T> clazz) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        return getPolicy().toBean(json, clazz);
    }

    /**
     * 将 JSON 字符串转换为复杂泛型对象
     *
     * @param json JSON 字符串
     * @param type 类型
     * @param <T>  类型
     * @return 对象
     */
    public static <T> T toBean(String json, Type type) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        return getPolicy().toBean(json, type);
    }

    /**
     * 将 JSON 字符串转换为对象
     *
     * @param json          JSON 字符串
     * @param typeReference 类型引用
     * @param <T>           类型
     * @return 对象
     */
    public static <T> T toBean(String json, TypeReference<T> typeReference) {
        if (typeReference == null) {
            return null;
        }
        return toBean(json, typeReference.getType());
    }

    /**
     * 将 JSON 字符串转换为对象
     *
     * @param json          JSON 字符串
     * @param typeReference 类型引用
     * @param ignoreError   是否忽略错误
     * @param <T>           类型
     * @return 对象
     */
    public static <T> T toBean(String json, TypeReference<T> typeReference, boolean ignoreError) {
        try {
            return toBean(json, typeReference);
        } catch (Exception e) {
            if (ignoreError) {
                return null;
            }
            throw e;
        }
    }

    /**
     * 将 JSON 字符串解析为通用对象
     *
     * @param json JSON 字符串
     * @return 对象
     */
    public static Object parseObj(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        return getPolicy().parseObj(json);
    }

    /**
     * 将 JSON 字符串转换为 List
     *
     * @param json  JSON 字符串
     * @param clazz 元素类型
     * @param <T>   类型
     * @return List
     */
    public static <T> List<T> toList(String json, Class<T> clazz) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        return getPolicy().toList(json, clazz);
    }

    /**
     * 获取当前的 JSON 序列化策略
     *
     * @return 序列化策略
     * @throws IllegalStateException 如果没有找到可用的策略
     */
    public static JsonSerializerPolicy getPolicy() {
        if (defaultPolicy == null) {
            throw new IllegalStateException("No JsonSerializerPolicy is available. "
                    + "Add com.team4u:team4u-serializer-jackson, or register/provide a custom "
                    + "JsonSerializerPolicy via ServiceLoader.");
        }
        return defaultPolicy;
    }
}
