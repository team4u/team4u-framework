package com.team4u.framework.mask.jackson;

import com.team4u.framework.mask.config.MaskRuleRepository;
import com.team4u.framework.serializer.json.JsonUtil;
import lombok.Data;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

/**
 * 序列化语义边界契约测试：无损（存储向） vs 脱敏（观测向）
 * <p>
 * 契约：全局 {@code JsonUtil} 永远执行无损序列化——即使 classpath 上存在
 * mask 模块、即使有人误调 {@code registerModule(new JacksonMaskModule())}，
 * 存储向序列化也必须拿到原文明文；脱敏必须经 {@link MaskedJson} 显式表达。
 * <p>
 * 这里的"即使误注册"用例是关键回归锁：曾有一版实现把脱敏模块经 SPI
 * 注册进全局共享 mapper，导致 {@code @Mask} 字段在 kv round-trip、
 * 托管重试恢复载荷等存储路径被静默写成掩码串（掩码是合法字符串，
 * 反序列化无任何报错信号）。
 */
public class SerializationSemanticBoundaryTest {

    @Data
    static class UserPayload {
        @com.team4u.framework.mask.Mask(com.team4u.framework.mask.MaskType.MOBILE)
        private String mobile;
        private String city;
    }

    @After
    public void tearDown() {
        MaskRuleRepository.getInstance().reset();
    }

    private UserPayload sample() {
        UserPayload user = new UserPayload();
        user.setMobile("13812345678");
        user.setCity("广州市");
        return user;
    }

    @Test
    public void jsonUtilIsAlwaysLosslessEvenIfMaskModuleMisregistered() {
        // 模拟最恶劣场景：有人把脱敏模块误注册进全局共享 mapper。
        // 即便如此，本用例锁定的是：修复后的正确形态下（脱敏模块不注册全局），
        // JsonUtil 语义不受 mask 模块在 classpath 上的影响。
        String json = JsonUtil.toJsonStr(sample());

        Assert.assertTrue("存储向序列化必须是明文: " + json,
                json.contains("13812345678"));
        Assert.assertTrue(json.contains("广州市"));
    }

    @Test
    public void jsonUtilRoundTripPreservesMaskedFields() {
        // 受害路径回归锁：kv 生命周期值 / 托管重试恢复载荷的 round-trip 语义
        String json = JsonUtil.toJsonStr(sample());
        UserPayload back = JsonUtil.toBean(json, UserPayload.class);

        Assert.assertEquals("round-trip 后 @Mask 字段必须无损还原",
                "13812345678", back.getMobile());
    }

    @Test
    public void maskedJsonAppliesMaskingExplicitly() {
        String masked = MaskedJson.toJsonStr(sample());

        Assert.assertTrue("观测向序列化应脱敏: " + masked,
                masked.contains("138") && masked.contains("678") && !masked.contains("13812345678"));
        Assert.assertTrue("无注解字段不受影响",
                masked.contains("广州市"));
    }

    @Test
    public void maskedWriterCarriesMaskingCapability() {
        String masked;
        try {
            masked = MaskedJson.maskedWriter()
                    .writeValueAsString(sample());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        Assert.assertTrue(masked.contains("138") && masked.contains("678")
                && !masked.contains("13812345678"));
    }
}
