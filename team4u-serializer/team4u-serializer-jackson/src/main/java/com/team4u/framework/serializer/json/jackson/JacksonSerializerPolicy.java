package com.team4u.framework.serializer.json.jackson;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.team4u.framework.policy.api.ContextPolicy;
import com.team4u.framework.serializer.json.JsonSerializerPolicy;

import java.lang.reflect.Type;
import java.util.List;

/**
 * 基于 Jackson 实现的 JSON 序列化策略
 *
 * @author jay.wu
 */
public class JacksonSerializerPolicy implements JsonSerializerPolicy {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    static {
        // 注册 Java8 时间模块
        OBJECT_MAPPER.registerModule(new JavaTimeModule());
        // 忽略未知的属性
        OBJECT_MAPPER.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        // 不包含 null 值
        OBJECT_MAPPER.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        // 格式化日期时不转为时间戳
        OBJECT_MAPPER.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
    }

    @Override
    public String toJsonStr(Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Convert object to json string error", e);
        }
    }

    @Override
    public <T> T toBean(String json, Class<T> clazz) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(json, clazz);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Convert json string to bean error", e);
        }
    }

    @Override
    public <T> T toBean(String json, Type type) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            JavaType javaType = OBJECT_MAPPER.getTypeFactory().constructType(type);
            return OBJECT_MAPPER.readValue(json, javaType);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Convert json string to bean error", e);
        }
    }

    @Override
    public <T> List<T> toList(String json, Class<T> clazz) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            JavaType type = OBJECT_MAPPER.getTypeFactory().constructCollectionType(List.class, clazz);
            return OBJECT_MAPPER.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Convert json string to list error", e);
        }
    }

    @Override
    public Object parseObj(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Parse json string error", e);
        }
    }

    @Override
    public boolean supports(Void context) {
        try {
            Class.forName("com.fasterxml.jackson.databind.ObjectMapper");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @Override
    public int priority() {
        return ContextPolicy.HIGH;
    }

    @Override
    public String key() {
        return "jackson";
    }
}
