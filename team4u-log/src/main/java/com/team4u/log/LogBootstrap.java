package com.team4u.log;

import com.team4u.framework.config.core.ConfigManager;
import com.team4u.log.config.LogConfigManager;
import com.team4u.log.core.LogEngine;

/**
 * 日志模块引导类
 * <p>
 * 负责在应用启动阶段初始化动态脱敏、染色限流以及配置中心集成。
 */
public class LogBootstrap {

    /**
     * 启动日志模块自举
     *
     * @param globalConfigManager 配置管理器
     */
    public static void start(ConfigManager globalConfigManager) {
        // 1. 初始化动态配置管理器
        LogConfigManager.getInstance().init(globalConfigManager);

        // 2. 初始化核心引擎
        LogEngine.getInstance();

        System.out.println("[Team4u-Log] Bootstrap initialized. Dynamic Masking & Targeted Dyeing enabled.");
    }
}
