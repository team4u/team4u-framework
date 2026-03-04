package com.team4u.log.config;

import cn.hutool.json.JSONUtil;
import cn.hutool.log.Log;
import com.team4u.framework.config.core.ConfigManager;
import com.team4u.framework.config.core.support.ConfigDrivenRegistry;
import lombok.Data;

/**
 * 成本与性能（FinOps）配置仓库
 * <p>
 * 统一管理限流阈值、最大日志截断长度等 FinOps 相关配置。
 */
public class FinOpsConfigRepository {
    private static final Log log = Log.get();
    private static final FinOpsConfigRepository INSTANCE = new FinOpsConfigRepository();
    private static final String CONFIG_KEY = "team4u.log.finops";

    private volatile FinOpsConfig config = new FinOpsConfig();
    private ConfigDrivenRegistry<FinOpsConfig> registry;

    private FinOpsConfigRepository() {
    }

    /**
     * 获取仓库单例
     */
    public static FinOpsConfigRepository getInstance() {
        return INSTANCE;
    }

    /**
     * 组件自治：自己初始化自己的配置监听
     */
    public void init(ConfigManager configManager) {
        this.registry = new ConfigDrivenRegistry<>(configManager, CONFIG_KEY, json -> {
            try {
                if (json == null || json.trim().isEmpty()) {
                    return new FinOpsConfig();
                }
                FinOpsConfig newConfig = JSONUtil.toBean(json, FinOpsConfig.class);
                this.config = newConfig != null ? newConfig : new FinOpsConfig();
                return this.config;
            } catch (Exception e) {
                log.error("FinOpsConfigRepository|parseConfig|error|msg={}", e.getMessage());
                return this.config;
            }
        });
        this.registry.get(CONFIG_KEY);
    }

    /**
     * 获取当前的 FinOps 配置
     */
    public FinOpsConfig get() {
        return config;
    }

    /**
     * FinOps 实体类
     */
    @Data
    public static class FinOpsConfig {
        /**
         * 整体日志最大长度（单位：字符，兜底保护）
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
