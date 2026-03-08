package com.team4u.framework.lease.jdbc.codec;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

import java.util.Collections;
import java.util.LinkedHashMap;
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
        return JSONUtil.toJsonStr(attributes);
    }

    public Map<String, String> fromJson(String json) {
        if (json == null || json.trim().isEmpty()) {
            return Collections.emptyMap();
        }
        JSONObject jsonObject = JSONUtil.parseObj(json);
        if (jsonObject.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> result = new LinkedHashMap<String, String>();
        for (Map.Entry<String, Object> entry : jsonObject.entrySet()) {
            Object value = entry.getValue();
            result.put(entry.getKey(), value == null ? null : String.valueOf(value));
        }
        return result;
    }
}
