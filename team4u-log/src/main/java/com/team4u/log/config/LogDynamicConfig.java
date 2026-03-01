package com.team4u.log.config;

import lombok.Data;
import org.slf4j.event.Level;

import java.util.List;
import java.util.Map;

/**
 * 动态日志配置模型
 */
@Data
public class LogDynamicConfig {

    /**
     * 掩码规则：ClassName -> (FieldName -> MaskPolicyKey)
     */
    private Map<String, Map<String, String>> maskRules;

    /**
     * 免侵入代理规则：ClassName -> ProxyRule
     */
    private Map<String, ProxyRule> proxyRules;
    /**
     * 动态染色规则列表
     */
    private List<DyeingRule> dyeingRules;
    /**
     * 限流及长度限制配置
     */
    private FinOpsConfig finOpsConfig;

    @Data
    public static class ProxyRule {
        /**
         * 允许拦截的方法名列表，配置 ["*"] 代表拦截所有 public 方法
         */
        private List<String> methods;
        /**
         * 慢日志阈值（毫秒）
         */
        private long slowThreshold = -1;
        /**
         * 需被视为业务异常而被降级打印的异常类名列表
         */
        private List<String> ignoreExceptions;
    }

    @Data
    public static class DyeingRule {
        private String id;
        private String condition; // 表达式（基于 team4u-criterion）
        private Level targetLevel;
    }

    @Data
    public static class FinOpsConfig {
        /**
         * 整体日志最大长度（兜底保护）
         */
        private int maxLogLength = 5000;
        /**
         * 单个字符串字段的最大长度（防止单个大报文/文件撑爆内存）
         */
        private int maxStringLength = 2000;
        /**
         * 每秒错误日志限流阈值
         */
        private int errorLimitPerSecond = 10;
    }
}
