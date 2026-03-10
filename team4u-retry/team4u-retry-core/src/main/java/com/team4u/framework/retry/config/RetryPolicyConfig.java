package com.team4u.framework.retry.config;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * 重试策略持久化配置模型
 * <p>
 * 用于与配置中心或本地属性文件映射，定义重试次数、退避规则等原始参数。
 *
 * @author jay.wu
 */
@Getter
@Setter
public class RetryPolicyConfig {
    /**
     * 最大重试次数（不包含首次执行）
     */
    private int maxRetries = 2;
    /**
     * 前台最大重试次数（不包含首次执行）
     */
    private Integer foregroundMaxRetries;
    /**
     * 退避配置
     */
    private BackoffConfig backoff;
    /**
     * 触发重试的异常列表
     */
    private List<String> retryOnExceptions;
    /**
     * 终止重试的异常列表
     */
    private List<String> abortOnExceptions;
    /**
     * 重试条件表达式
     */
    private String condition;

}
