package com.team4u.framework.retry.config;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * 重试策略配置定义
 *
 * @author jay.wu
 */
@Getter
@Setter
public class RetryPolicyConfig {
    /**
     * 最大尝试次数（包含首次执行）
     */
    private int maxAttempts = 3;
    /**
     * 本地进程内尝试次数
     */
    private Integer localAttempts;
    /**
     * 退避类型：fixed, increment, exponential, exponentialJitter
     */
    private String backoffType = "fixed";
    /**
     * 初始延迟（毫秒）
     */
    private long initialDelay = 1000;
    /**
     * 乘数或步进值
     */
    private double multiplier = 2.0;
    /**
     * 最大延迟（毫秒）
     */
    private long maxDelay = 30000;
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
