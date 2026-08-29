package com.team4u.framework.mask.jackson;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team4u.framework.serializer.json.jackson.JacksonSerializerPolicy;

/**
 * 观测向（脱敏）JSON 序列化门面
 * <p>
 * <b>契约</b>：全局共享 mapper（{@link JacksonSerializerPolicy#sharedMapper()}，
 * 即 {@code JsonUtil} 背后的 mapper）<b>永远执行无损序列化</b>——
 * 存库、缓存、重放载荷、跨进程传输等场景必须拿到原文明文；
 * {@code @Mask} 脱敏属于<b>观测语义</b>（日志、审计、对外展示），
 * 必须经由本门面显式表达意图，绝不作为 JsonUtil 的默认行为。
 * <p>
 * 因此 {@link JacksonMaskModule} <b>不注册</b>进全局共享 mapper：
 * 若注册，所有走 {@code JsonUtil} 的存储向序列化（如 kv 生命周期值的
 * round-trip、托管重试的恢复载荷）会把 {@code @Mask} 字段悄悄写成掩码串，
 * 造成无报错的静默数据损坏——掩码串是合法字符串，反序列化不会有任何信号。
 * <p>
 * 本门面以共享 mapper 的副本为基底（继承基础配置与其他安全模块），
 * 叠加 {@link JacksonMaskModule}，隔离脱敏语义。副本不回写共享 mapper，
 * 也不会影响任何 {@code JsonUtil} 消费方。
 *
 * <pre>{@code
 * // 存数据库：永远明文（物理上不可能脱敏）
 * String plain = JsonUtil.toJsonStr(user);
 *
 * // 打日志 / 对外输出：显式声明观测语义，输出脱敏 JSON
 * String masked = MaskedJson.toJsonStr(user);
 * }</pre>
 *
 * @author jay.wu
 */
public final class MaskedJson {

    /**
     * 脱敏 mapper：共享 mapper 副本 + 脱敏模块。
     * 类加载时一次性构建；模块实例无状态（规则运行期动态查询），无需重建。
     */
    private static final ObjectMapper MASKED_MAPPER = buildMapper();

    private MaskedJson() {
    }

    private static ObjectMapper buildMapper() {
        ObjectMapper mapper = JacksonSerializerPolicy.sharedMapper().copy();
        mapper.registerModule(new JacksonMaskModule());
        return mapper;
    }

    /**
     * 序列化为脱敏 JSON 字符串（观测向：日志、审计、对外展示）
     *
     * @param obj 待序列化对象，null 返回 null
     * @return 应用了 {@code @Mask} 注解与脱敏规则的 JSON 字符串
     */
    public static String toJsonStr(Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            return MASKED_MAPPER.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Convert object to masked json string error", e);
        }
    }

    /**
     * 获取脱敏 mapper 的只写视图（用于需要 writer 属性的场景，如注入
     * {@link MaskConfig} 截断配置）。
     * <p>
     * 返回的 {@code ObjectWriter} 每次调用新建，携带属性互不影响；
     * 其背后的 mapper 为门面私有的脱敏副本，不影响全局共享 mapper。
     *
     * @return 绑定了脱敏模块的 ObjectWriter
     */
    public static com.fasterxml.jackson.databind.ObjectWriter maskedWriter() {
        return MASKED_MAPPER.writer();
    }
}
