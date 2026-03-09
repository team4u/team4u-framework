package com.team4u.framework.retry.config;

import lombok.Getter;
import lombok.Setter;

import java.util.Map;

/**
 * 退避策略配置模型
 *
 * @author jay.wu
 */
@Getter
@Setter
@lombok.EqualsAndHashCode
public class BackoffConfig {
    /**
     * 退避类型：fixed, increment, exponential, exponentialJitter
     */
    private String type = "fixed";

    /**
     * 退避策略参数
     */
    private Map<String, Object> params;
}
