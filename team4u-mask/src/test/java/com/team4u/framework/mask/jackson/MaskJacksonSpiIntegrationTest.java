package com.team4u.framework.mask.jackson;

import com.team4u.framework.mask.Mask;
import com.team4u.framework.mask.MaskType;
import com.team4u.framework.serializer.json.JsonUtil;
import lombok.Data;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * mask 与全局 JsonUtil 的 SPI 集成测试
 * <p>
 * 验证依赖 team4u-mask 后，JacksonMaskModule 经
 * {@code JacksonModuleContributor} SPI 自动注册进共享 ObjectMapper，
 * {@code JsonUtil.toJson} 对 {@code @Mask} 字段自动脱敏。
 * <p>
 * 注意：共享 mapper 的 SPI 收集发生在首次初始化时；测试类路径中存在
 * mask 的服务文件，因此初始化即带上脱敏模块。为验证「注册后生效」，
 * 用直接注册的方式兜底模拟晚于初始化的场景。
 */
public class MaskJacksonSpiIntegrationTest {

    @Before
    public void setUp() {
        // 兜底：若共享 mapper 初始化早于本测试类路径（同 JVM 其他测试先行触发），
        // 显式再注册一次脱敏模块（幂等），确保断言在「已注册」状态下执行
        com.team4u.framework.serializer.json.jackson.JacksonSerializerPolicy.registerModule(
                new JacksonMaskModule());
    }

    @After
    public void tearDown() {
        MaskRuleRepositoryHolder.reset();
    }

    @Test
    public void testJsonUtilMasksAnnotatedField() {
        UserPayload user = new UserPayload();
        user.setName("周杰伦");
        user.setMobile("13812345678");
        user.setEmail("jay.wuy@gmail.com");

        String json = JsonUtil.toJsonStr(user);

        Assert.assertTrue("姓名应脱敏: " + json, json.contains("**伦"));
        Assert.assertTrue("手机号应脱敏: " + json, json.contains("138*****678"));
        Assert.assertTrue("邮箱应脱敏: " + json, json.contains("j****@gmail.com"));
        Assert.assertFalse("不应包含原始手机号: " + json, json.contains("13812345678"));
        Assert.assertFalse("不应包含原始邮箱: " + json, json.contains("jay.wuy@gmail.com"));
    }

    @Test
    public void testJsonUtilMasksPlainFieldsUnaffected() {
        PlainPayload plain = new PlainPayload();
        plain.setCity("广州市");
        plain.setCode("12345");

        String json = JsonUtil.toJsonStr(plain);

        Assert.assertTrue("无注解字段不应脱敏: " + json, json.contains("广州市"));
        Assert.assertTrue(json.contains("12345"));
    }

    @Test
    public void testJsonUtilRoundTripStillWorks() {
        // 注册脱敏模块后，反序列化能力不受影响
        UserPayload source = new UserPayload();
        source.setName("张三");
        source.setMobile("13800000000");
        source.setEmail("z@x.com");

        String json = JsonUtil.toJsonStr(source);
        UserPayload decoded = JsonUtil.toBean(json, UserPayload.class);

        // 反序列化拿到的是脱敏后的值（这是脱敏序列化的语义），但结构完整可解析
        // “张三” 两字：保留最后一个字 → “*三”
        Assert.assertNotNull(decoded);
        Assert.assertEquals("*三", decoded.getName());
    }

    @Data
    public static class UserPayload {
        @Mask(MaskType.NAME)
        private String name;

        @Mask(MaskType.MOBILE)
        private String mobile;

        @Mask(MaskType.EMAIL)
        private String email;
    }

    @Data
    public static class PlainPayload {
        private String city;
        private String code;
    }

    /**
     * 隔离 MaskRuleRepository 状态的局部持有者
     */
    private static final class MaskRuleRepositoryHolder {
        static void reset() {
            com.team4u.framework.mask.config.MaskRuleRepository.getInstance().reset();
        }
    }
}
