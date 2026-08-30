package com.team4u.framework.singleflight.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.team4u.framework.serializer.json.JsonUtil;
import com.team4u.framework.singleflight.api.SingleFlightConfigException;

import java.lang.reflect.Type;

/**
 * 结果编解码器：加载结果与 kv 存储之间的 JSON 序列化边界。
 * <p>
 * null 与 void 统一归一化为 JSON null；不支持 JSON 序列化的类型视为配置错误。
 * 等待者复用终态会话时按声明的返回类型反序列化，与执行者本地对象不是同一个实例。
 * </p>
 *
 * @author jay.wu
 */
final class ResultCodec {

    private ResultCodec() {
    }

    /**
     * 结果序列化为 JSON 树：null 与 void 归一化为 JSON null。
     */
    static JsonNode toJson(Object result, Type returnType) {
        if (result == null || void.class.equals(returnType) || Void.TYPE.equals(returnType)) {
            return NullNode.getInstance();
        }
        Object parsed = JsonUtil.parseObj(JsonUtil.toJsonStr(result));
        if (parsed instanceof JsonNode) {
            return (JsonNode) parsed;
        }
        throw new SingleFlightConfigException(
                "Singleflight result is not JSON-serializable|type=" + result.getClass().getName());
    }

    /**
     * 结果 JSON 反序列化为目标返回类型；void 返回类型恒为 null。
     */
    static Object decode(String json, Type returnType) {
        if (void.class.equals(returnType) || Void.TYPE.equals(returnType)) {
            return null;
        }
        return JsonUtil.toBean(json, returnType);
    }
}
