package com.team4u.log.config;

import com.team4u.framework.config.core.ConfigManager;
import com.team4u.framework.config.core.support.ConfigDrivenRegistry;
import com.team4u.log.core.LogEngine;
import com.team4u.log.mask.config.MaskRuleRepository;
import com.team4u.log.pipeline.interceptor.RateLimitInterceptor;
import com.team4u.log.pipeline.interceptor.TargetedDyeingInterceptor;

/**
 * 动态配置管理器
 * <p>
 * 对接配置中心（team4u-config），利用 ConfigDrivenRegistry 维护配置的生命周期。
 */
public class LogConfigManager {

    /**
     * 配置键名
     */
    private static final String CONFIG_KEY = "team4u.log.config";

    private static final LogConfigManager INSTANCE = new LogConfigManager();

    private final LogConfigParser parser = new LogConfigParser();

    /**
     * 配置驱动注册表
     */
    private ConfigDrivenRegistry<LogDynamicConfig> registry;

    private LogConfigManager() {
    }

    /**
     * 获取配置管理器单例实例
     *
     * @return LogConfigManager 实例
     */
    public static LogConfigManager getInstance() {
        return INSTANCE;
    }

    /**
     * 初始化配置管理器
     *
     * @param globalConfigManager 全局配置管理器
     */
    public void init(ConfigManager globalConfigManager) {
        this.registry = new ConfigDrivenRegistry<>(globalConfigManager, "team4u.log", json -> {
            LogDynamicConfig config = parser.parse(json);
            // 解析并分发配置到子系统
            applySubsystemUpdate(config);
            return config;
        });

        // 首次加载配置
        this.registry.get(CONFIG_KEY);
    }

    /**
     * 将配置应用到各子系统
     */
    private void applySubsystemUpdate(LogDynamicConfig newConfig) {
        if (newConfig == null) {
            return;
        }

        // 1. 更新脱敏规则
        if (newConfig.getMaskRules() != null) {
            MaskRuleRepository.getInstance().refreshRules(newConfig.getMaskRules());
        }

        // 2. 更新日志染色规则
        if (newConfig.getDyeingRules() != null) {
            TargetedDyeingInterceptor.getInstance().refreshRules(newConfig.getDyeingRules());
        }

        // 3. 更新限流及长度限制
        if (newConfig.getFinOpsConfig() != null) {
            RateLimitInterceptor.getInstance().updateLimit(newConfig.getFinOpsConfig().getErrorLimitPerSecond());
            LogEngine.getInstance().setMaxLogLength(newConfig.getFinOpsConfig().getMaxLogLength());
            LogEngine.getInstance().setMaxStringLength(newConfig.getFinOpsConfig().getMaxStringLength());
        }
    }

    public LogDynamicConfig getCurrentConfig() {
        if (registry == null) {
            return new LogDynamicConfig();
        }
        return registry.get(CONFIG_KEY);
    }
}
