package com.team4u.framework.singleflight.policy;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.team4u.framework.base.util.TypeReference;
import com.team4u.framework.serializer.json.JsonUtil;
import com.team4u.framework.serializer.json.jackson.JacksonSerializerPolicy;
import com.team4u.framework.singleflight.api.SingleFlightConfigException;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Type;
import java.time.LocalDate;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

/**
 * FallbackConverter 的聚焦测试：降级 bean 转换必须与应用显式选择的
 * {@code JsonSerializerPolicy}（这里是 team4u-serializer-jackson 的共享 mapper：
 * 忽略未知属性、JavaTimeModule、注册的自定义模块）语义一致，
 * singleflight core 不得私建 ObjectMapper 形成第二套序列化语义。
 */
public class FallbackConverterTest {

    private final FallbackConverter converter = new FallbackConverter();

    /** 降级 POJO：只有 name 字段，用于未知属性语义 */
    public static class User {
        private String name;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    /** 证明共享 provider 路径的哨兵类型：仅本测试可见，模块注册不外溢 */
    public static class ProviderSentinel {
        private String value;

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }
    }

    /**
     * 局部注册：模块只覆盖本测试私有的 ProviderSentinel 类型，对其他类型零影响，
     * 因此无需卸载（与 serializer-jackson 的 JacksonModuleRegistrationTest 同一实践）。
     * 若 FallbackConverter 走 JsonUtil/共享 mapper，此模块即生效；
     * 若私建 bare mapper，此模块永远不可见。
     */
    @BeforeClass
    public static void registerSentinelModuleOnSharedProvider() {
        SimpleModule module = new SimpleModule("FallbackConverterProviderSentinelModule");
        module.addDeserializer(ProviderSentinel.class, new JsonDeserializer<ProviderSentinel>() {
            @Override
            public ProviderSentinel deserialize(JsonParser p, DeserializationContext ctxt)
                    throws IOException {
                JsonNode node = p.readValueAsTree();
                ProviderSentinel sentinel = new ProviderSentinel();
                sentinel.setValue("provider:" + node.get("value").asText());
                return sentinel;
            }
        });
        JacksonSerializerPolicy.registerModule(module);
    }

    private static JsonNode json(String json) {
        return (JsonNode) JsonUtil.parseObj(json);
    }

    @Test
    public void unknownJsonFieldConvertsToPojo() {
        // 规则解析（RuleCompiler）忽略未知属性；同一份降级 JSON 的 bean 转换
        // 必须同样忽略未知属性，否则规则能加载、降级却炸——语义割裂
        Object converted = converter.convert(
                json("{\"name\":\"a\",\"unknownField\":\"x\"}"), User.class);

        assertEquals("a", ((User) converted).getName());
    }

    @Test
    public void localDateFallbackConvertsWithJavaTimeSupport() {
        // 共享 mapper 注册了 JavaTimeModule 且不写时间戳；降级值携带日期是常见配置
        Object converted = converter.convert(json("\"2025-08-29\""), LocalDate.class);

        assertEquals(LocalDate.of(2025, 8, 29), converted);
    }

    @Test
    public void customModuleOnSharedProviderIsHonored() {
        Object converted = converter.convert(json("{\"value\":\"raw\"}"), ProviderSentinel.class);

        assertEquals("provider:raw", ((ProviderSentinel) converted).getValue());
    }

    @Test
    public void genericTypeFallbackKeepsExistingSemantics() {
        Type userList = new TypeReference<List<User>>() {
        }.getType();

        List<?> converted = (List<?>) converter.convert(
                json("[{\"name\":\"a\"}]"), userList);

        assertEquals(1, converted.size());
        assertEquals("a", ((User) converted.get(0)).getName());
    }

    @Test
    public void explicitJsonNullReturnsNullForObjectType() {
        assertNull(converter.convert(json("null"), String.class));
        assertNull(converter.convert(null, String.class));
    }

    @Test
    public void primitiveReturnTypeRejectsExplicitNullFallback() {
        assertPrimitiveNullRejected(json("null"));
        assertPrimitiveNullRejected(null);
    }

    private void assertPrimitiveNullRejected(JsonNode fallback) {
        try {
            converter.convert(fallback, int.class);
            fail("primitive return type must reject explicit null fallback");
        } catch (SingleFlightConfigException expected) {
            // 与引擎执行期组合校验（EffectivePolicies）同消息语义
        }
    }

    @Test
    public void invalidFallbackStillWrapsSingleFlightConfigException() {
        try {
            converter.convert(json("\"not-a-user\""), User.class);
            fail("mismatched fallback json must be a config error");
        } catch (SingleFlightConfigException expected) {
            // 保持 master 异常契约：消息前缀 + 原始 cause 不吞
        }
    }
}
