package com.team4u.framework.id.group;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 分组配置
 * <p>
 * 分组标识参与计数键的组成（{@code {规则标识}.{分组标识}}），分组标识变化后
 * 计数自然从头开始，实现周期重置与业务维度隔离。不配置分组时所有请求共享同一计数器。
 * </p>
 *
 * @author jay.wu
 */
@Data
@Accessors(chain = true)
public class SeqGroupConfig {

    /**
     * 分组策略标识，见 {@link GroupKeyPolicy#key()}；默认 {@link DateGroupKeyPolicy#KEY}
     */
    private String type = DateGroupKeyPolicy.KEY;

    /**
     * DATE 策略：分组标识的时间格式，如 yyyyMMdd（按天）、yyyyMM（按月）
     */
    private String format = "yyyyMMdd";

    /**
     * EXT 策略：从调用上下文（ext 属性）取值的键
     */
    private String extKey;

    /**
     * 自定义分组策略的扩展参数
     */
    private java.util.Map<String, String> attrs;
}
