package com.team4u.framework.id.group;

import com.team4u.framework.policy.api.KeyedPolicy;

import java.time.Clock;
import java.util.Map;

/**
 * 分组键策略：输出计数键的分组标识
 * <p>
 * 基于 {@link KeyedPolicy} 注册到 {@link GroupKeyPolicies}，策略标识即配置中的
 * {@code group.type}。自定义策略实现本接口并注册即可，扩展参数经
 * {@link SeqGroupConfig#getAttrs()} 透传。
 * </p>
 * 输出约束：分组标识不允许包含 {@code :}（与 kv 组件 {@code SpaceKey} 的
 * 键约束一致，避免物理键编解码歧义）。
 *
 * @author jay.wu
 */
public interface GroupKeyPolicy extends KeyedPolicy<String> {

    /**
     * 生成分组标识
     * <p>
     * 返回 {@code null} 或空串视为无分组（与未配置分组等价）。
     * 无法生成分组（如上下文缺少必要属性）时应抛 {@code SeqConfigException}。
     * </p>
     */
    String groupKey(Context context);

    /**
     * 分组上下文
     */
    @lombok.Data
    class Context {

        /**
         * 规则标识
         */
        private final String ruleId;

        /**
         * 分组配置
         */
        private final SeqGroupConfig config;

        /**
         * 调用方透传的扩展属性
         */
        private final Map<String, Object> ext;

        /**
         * 时钟（测试可注入虚拟时钟推进时间）
         */
        private final Clock clock;
    }
}
