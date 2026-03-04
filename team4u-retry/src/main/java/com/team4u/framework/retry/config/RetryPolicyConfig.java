package com.team4u.framework.retry.config;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * 映射配置中心的重试策略定义
 *
 * @author jay.wu
 */
@Getter
@Setter
public class RetryPolicyConfig {
    /**
     * 最大尝试次数
     */
    private int maxAttempts = 3;
    /**
     * 退避类型：fixed, increment, exponential, exponentialJitter
     */
    private String backoffType = "fixed";
    /**
     * 初始延迟（毫秒）
     */
    private long initialDelay = 1000;
    /**
     * 步进值或乘数
     */
    private double multiplier = 2.0;
    /**
     * 最大延迟（毫秒）
     */
    private long maxDelay = 30000;
    /**
     * 允许重试的异常类列表
     */
    private List<String> retryOnExceptions;
    /**
     * 终止重试的异常类列表
     */
    private List<String> abortOnExceptions;
    /**
     * 高级重试条件表达式 (team4u-criterion)
     */
    private String condition;
}
