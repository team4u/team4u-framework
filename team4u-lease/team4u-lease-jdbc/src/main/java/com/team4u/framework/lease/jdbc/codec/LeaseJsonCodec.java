package com.team4u.framework.lease.jdbc.codec;

import com.team4u.framework.serializer.json.JsonUtil;

import java.util.Collections;
import java.util.Map;

/**
 * 租赁任务属性的 JSON 编解码器
 *
 * @author jay.wu
 */
public class LeaseJsonCodec {

    public String toJson(Map<String, String> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return "{}";
        }
        return JsonUtil.toJsonStr(attributes);
    }

    @SuppressWarnings("unchecked")
    public Map<String, String> fromJson(String json) {
        if (json == null || json.trim().isEmpty()) {
            return Collections.emptyMap();
        }
        return JsonUtil.toBean(json, Map.class);
    }
}
