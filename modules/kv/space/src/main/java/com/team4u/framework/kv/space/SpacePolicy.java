package com.team4u.framework.kv.space;

import com.team4u.framework.policy.api.KeyedPolicy;

/**
 * 键空间策略：一个键空间的元数据与默认行为
 * <p>
 * 注册到 {@link Spaces} 的注册表后，可按名称解析并构建类型化的
 * {@link Space} 门面。策略可由配置中心下发后重新注册，实现热更新
 * （注册表为 Copy-On-Write，读路径无锁）。
 * </p>
 *
 * @author jay.wu
 */
@lombok.Data
@lombok.EqualsAndHashCode(of = "name")
@lombok.experimental.Accessors(chain = true)
public class SpacePolicy implements KeyedPolicy<String> {

    /**
     * 默认有效期：永不过期
     */
    public static final long DEFAULT_TTL_MILLIS = 0;

    /**
     * 键空间名（唯一标识）
     */
    private String name;

    /**
     * 值类型：供 {@link Space} 门面反序列化使用
     */
    private Class<?> valueType = String.class;

    /**
     * 默认有效期（毫秒），0 为永不过期
     */
    private long defaultTtlMillis = DEFAULT_TTL_MILLIS;

    @Override
    public String key() {
        return name;
    }
}
