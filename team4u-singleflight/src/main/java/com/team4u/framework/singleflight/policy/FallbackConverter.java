package com.team4u.framework.singleflight.policy;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.team4u.framework.singleflight.api.SingleFlightConfigException;

import java.lang.reflect.Type;

/**
 * Fallback conversion from native rule JSON to the execution return type.
 *
 * @author jay.wu
 */
public class FallbackConverter {

    private static final TypeFactory TYPE_FACTORY = TypeFactory.defaultInstance();
    private static final ObjectMapper MAPPER = new ObjectMapper();

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
            return MAPPER.readValue(MAPPER.treeAsTokens(fallback), javaType);
        } catch (Exception e) {
            throw new SingleFlightConfigException(
                    "Invalid fallback json|returnType=" + javaType + "|fallback=" + fallback, e);
        }
    }
}
