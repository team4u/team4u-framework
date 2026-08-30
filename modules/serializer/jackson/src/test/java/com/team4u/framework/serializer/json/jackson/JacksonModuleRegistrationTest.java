package com.team4u.framework.serializer.json.jackson;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.team4u.framework.serializer.json.JsonUtil;
import com.team4u.framework.serializer.json.JsonUtilTest;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * JacksonSerializerPolicy 模块注册扩展点的行为测试
 * <p>
 * 注意：模块注册作用于全局共享 mapper（静态状态），因此所有测试均使用
 * 各自专属的哨兵类型，避免测试间以及与 {@code JsonUtilTest} 的串扰。
 */
public class JacksonModuleRegistrationTest {

    ////////////////////////////////////////////////////////////
    // 哨兵类型：每个测试专用，互不影响
    ////////////////////////////////////////////////////////////

    /** 静态注册测试专用 */
    public static class StaticSentinel {
        public String value = "raw";
    }

    /** 晚注册（缓存刷新）测试专用 */
    public static class LateSentinel {
        public String value = "raw";
    }

    /** 批量注册测试专用 */
    public static class BatchSentinel {
        public String value = "raw";
    }

    /** SPI 贡献模块测试专用（由 TestModuleContributor 处理） */
    public static class SpiSentinel {
        public String value = "raw";
    }

    /** 幂等测试专用 */
    public static class IdempotentSentinel {
        public String value = "raw";
    }

    ////////////////////////////////////////////////////////////
    // 测试
    ////////////////////////////////////////////////////////////

    @Test
    public void testRegisterModuleTakesEffect() {
        SimpleModule module = new SimpleModule("StaticTestModule");
        module.addSerializer(StaticSentinel.class, new JsonSerializer<StaticSentinel>() {
            @Override
            public void serialize(StaticSentinel value, JsonGenerator gen, SerializerProvider serializers)
                    throws IOException {
                gen.writeString("static:" + value.value);
            }
        });

        Assert.assertTrue(JacksonSerializerPolicy.registerModule(module));
        Assert.assertEquals("\"static:raw\"", JsonUtil.toJsonStr(new StaticSentinel()));
    }

    @Test
    public void testSharedMapperIsPolicyMapper() throws Exception {
        // sharedMapper 与 JsonUtil 底层是同一实例，行为完全一致
        ObjectMapper mapper = JacksonSerializerPolicy.sharedMapper();
        StaticSentinel sentinel = new StaticSentinel();
        Assert.assertSame(mapper, JacksonSerializerPolicy.sharedMapper());
        Assert.assertEquals(
                JsonUtil.toJsonStr(sentinel),
                mapper.writeValueAsString(sentinel)
        );
    }

    @Test
    public void testRegisterModuleNullRejected() {
        try {
            JacksonSerializerPolicy.registerModule(null);
            Assert.fail("null module should be rejected");
        } catch (IllegalArgumentException expected) {
        }
        try {
            JacksonSerializerPolicy.registerModules(null);
            Assert.fail("null modules should be rejected");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void testDuplicateRegistrationIdempotent() {
        SimpleModule module = new SimpleModule("IdempotentTestModule");
        module.addSerializer(IdempotentSentinel.class, new JsonSerializer<IdempotentSentinel>() {
            @Override
            public void serialize(IdempotentSentinel value, JsonGenerator gen, SerializerProvider serializers)
                    throws IOException {
                // 若被叠加应用两次，结果会变成 mark:mark:raw
                gen.writeString("mark:" + value.value);
            }
        });

        boolean first = JacksonSerializerPolicy.registerModule(module);
        if (first) {
            Assert.assertEquals("\"mark:raw\"", JsonUtil.toJsonStr(new IdempotentSentinel()));
        }

        // 同实现类 + 同名模块重复注册：返回 false 且行为不变（不叠加）
        SimpleModule duplicate = new SimpleModule("IdempotentTestModule");
        duplicate.addSerializer(IdempotentSentinel.class, new JsonSerializer<IdempotentSentinel>() {
            @Override
            public void serialize(IdempotentSentinel value, JsonGenerator gen, SerializerProvider serializers)
                    throws IOException {
                gen.writeString("mark:" + value.value);
            }
        });
        Assert.assertFalse(JacksonSerializerPolicy.registerModule(duplicate));
        Assert.assertEquals("\"mark:raw\"", JsonUtil.toJsonStr(new IdempotentSentinel()));
    }

    @Test
    public void testRegisterModulesBatchWithNullElements() {
        SimpleModule module = new SimpleModule("BatchTestModule");
        module.addSerializer(BatchSentinel.class, new JsonSerializer<BatchSentinel>() {
            @Override
            public void serialize(BatchSentinel value, JsonGenerator gen, SerializerProvider serializers)
                    throws IOException {
                gen.writeString("batch:" + value.value);
            }
        });

        // null 元素被忽略，不抛异常
        Assert.assertTrue(JacksonSerializerPolicy.registerModules(Arrays.asList(module, null)));
        Assert.assertEquals("\"batch:raw\"", JsonUtil.toJsonStr(new BatchSentinel()));
    }

    @Test
    public void testLateRegistrationRefreshesCachedSerializers() {
        // 1) 先用 JsonUtil 序列化，预热共享 mapper 对该类型的序列化器缓存
        Assert.assertEquals("{\"value\":\"raw\"}", JsonUtil.toJsonStr(new LateSentinel()));

        // 2) 注册改变该类型行为的模块
        SimpleModule lateModule = new SimpleModule("LateTestModule");
        lateModule.addSerializer(LateSentinel.class, new JsonSerializer<LateSentinel>() {
            @Override
            public void serialize(LateSentinel value, JsonGenerator gen, SerializerProvider serializers)
                    throws IOException {
                gen.writeString("late:" + value.value);
            }
        });
        Assert.assertTrue(JacksonSerializerPolicy.registerModule(lateModule));

        // 3) 晚注册的模块立即生效：不会被已缓存的序列化器屏蔽
        Assert.assertEquals("\"late:raw\"", JsonUtil.toJsonStr(new LateSentinel()));
        try {
            Assert.assertEquals(
                    "\"late:raw\"",
                    JacksonSerializerPolicy.sharedMapper().writeValueAsString(new LateSentinel())
            );
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void testServiceLoaderContributedModule() {
        // TestModuleContributor 通过测试 classpath 的服务文件注册，
        // 在共享 mapper 首次初始化时被 ServiceLoader 收集，无需手动注册
        Assert.assertEquals("\"spi:raw\"", JsonUtil.toJsonStr(new SpiSentinel()));
    }

    @Test
    public void testCoreFunctionsUnaffectedByModules() {
        // 注册扩展模块不影响既有基础能力
        List<String> list = Collections.singletonList("abc");
        String json = JsonUtil.toJsonStr(list);
        Assert.assertEquals(list, JsonUtil.toList(json, String.class));

        String beanJson = "{\"name\":\"jay\",\"unknownField\":1}";
        JsonUtilTest.User user = JsonUtil.toBean(beanJson, JsonUtilTest.User.class);
        Assert.assertEquals("jay", user.getName());
    }

    /**
     * SPI 测试用贡献者：通过
     * META-INF/services/com.team4u.framework.serializer.json.jackson.JacksonModuleContributor
     * 服务文件注册到测试 classpath
     */
    public static class TestModuleContributor implements JacksonModuleContributor {

        @Override
        public Collection<Module> modules() {
            SimpleModule module = new SimpleModule("SpiTestModule");
            module.addSerializer(SpiSentinel.class, new JsonSerializer<SpiSentinel>() {
                @Override
                public void serialize(SpiSentinel value, JsonGenerator gen, SerializerProvider serializers)
                        throws IOException {
                    gen.writeString("spi:" + value.value);
                }
            });
            return Collections.singletonList(module);
        }
    }
}
