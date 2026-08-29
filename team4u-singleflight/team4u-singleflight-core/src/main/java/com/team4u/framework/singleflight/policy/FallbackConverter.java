package com.team4u.framework.singleflight.policy;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.team4u.framework.serializer.json.JsonUtil;
import com.team4u.framework.singleflight.api.SingleFlightConfigException;

import java.lang.reflect.Type;

/**
 * 降级值转换器：把规则中的原生降级 JSON 反序列化为执行请求的返回类型。
 * <p>
 * 显式 JSON {@code null} 表示降级返回 null；返回类型为基本类型时无法承载 null，
 * 视为配置错误（引擎在执行期组合校验中同样拦截）。
 * </p>
 * <p>
 * <b>序列化语义边界</b>：降级值的 bean 转换走 {@link JsonUtil}（provider-free 门面），
 * 即应用显式选择的 JSON provider 语义——与规则解析（RuleCompiler）、结果编解码
 * （ResultCodec）同一条路，共享 mapper 的模块（JavaTimeModule、脱敏、截断、
 * 自定义模块）对降级值同样生效，忽略未知属性等行为也一致；不会出现
 * 「规则能加载、降级却炸」的语义割裂。本类不私建 ObjectMapper。
 * singleflight core 的直连 jackson-databind 边界仅剩 durable schema
 * （{@link JsonNode} 树模型 + {@link TypeFactory} 类型自省），不涉及任何
 * 序列化配置语义；应用必须显式提供 JSON provider，core 不传递。
 * </p>
 *
 * @author jay.wu
 */
public class FallbackConverter {

    /**
     * 类型自省工厂（durable-schema 豁免边界）：仅做 {@code Type → JavaType}
     * 的只读解析（primitive 判定与异常消息），不携带任何序列化配置，
     * 与共享 mapper 的模块注册完全无关。
     */
    private static final TypeFactory TYPE_FACTORY = TypeFactory.defaultInstance();

    /**
     * 转换降级 JSON 为目标类型；JSON 与类型不匹配视为配置错误。
     *
     * @param fallback   规则配置的降级 JSON（可能为 null 或 JSON null）
     * @param returnType 执行请求的返回类型
     * @return 反序列化后的降级值，可为 null
     */
    public Object convert(JsonNode fallback, Type returnType) {
        JavaType javaType = TYPE_FACTORY.constructType(returnType);
        if (fallback == null || fallback.isNull()) {
            if (javaType.isPrimitive()) {
                throw new SingleFlightConfigException(
                        "Primitive return type does not allow explicit null fallback|returnType=" + javaType);
            }
            return null;
        }
        try {
            // JsonNode 的规范 JSON 文本交给 JsonUtil（应用显式 provider）按 Type 转换，
            // provider 语义（JavaTime、自定义模块、忽略未知属性等）与 master 一致
            return JsonUtil.toBean(fallback.toString(), returnType);
        } catch (Exception e) {
            throw new SingleFlightConfigException(
                    "Invalid fallback json|returnType=" + javaType + "|fallback=" + fallback, e);
        }
    }
}
