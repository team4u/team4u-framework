package com.team4u.framework.singleflight.policy;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.team4u.framework.serializer.json.jackson.JacksonSerializerPolicy;
import com.team4u.framework.singleflight.api.SingleFlightConfigException;

import java.lang.reflect.Type;

/**
 * 降级值转换器：把规则中的原生降级 JSON 反序列化为执行请求的返回类型。
 * <p>
 * 显式 JSON {@code null} 表示降级返回 null；返回类型为基本类型时无法承载 null，
 * 视为配置错误（引擎在执行期组合校验中同样拦截）。
 * 序列化纯属只读的树到值转换（treeAsTokens → readValue），无模块或
 * writer 级特殊配置，直接复用 {@link JacksonSerializerPolicy#sharedMapper()}
 * 共享 mapper；若未来需要局部序列化定制，应使用
 * {@code sharedMapper().writer(...)} 变体而非私建 mapper。
 * </p>
 *
 * @author jay.wu
 */
public class FallbackConverter {

    /**
     * 转换降级 JSON 为目标类型；JSON 与类型不匹配视为配置错误。
     *
     * @param fallback   规则配置的降级 JSON（可能为 null 或 JSON null）
     * @param returnType 执行请求的返回类型
     * @return 反序列化后的降级值，可为 null
     */
    public Object convert(JsonNode fallback, Type returnType) {
        JavaType javaType = JacksonSerializerPolicy.sharedMapper().constructType(returnType);
        if (fallback == null || fallback.isNull()) {
            if (javaType.isPrimitive()) {
                throw new SingleFlightConfigException(
                        "Primitive return type does not allow explicit null fallback|returnType=" + javaType);
            }
            return null;
        }
        try {
            return JacksonSerializerPolicy.sharedMapper()
                    .readValue(JacksonSerializerPolicy.sharedMapper().treeAsTokens(fallback), javaType);
        } catch (Exception e) {
            throw new SingleFlightConfigException(
                    "Invalid fallback json|returnType=" + javaType + "|fallback=" + fallback, e);
        }
    }
}
