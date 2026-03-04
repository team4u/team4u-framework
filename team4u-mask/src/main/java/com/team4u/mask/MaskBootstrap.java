package com.team4u.mask;

import com.team4u.framework.config.core.ConfigManager;
import com.team4u.mask.config.MaskRuleRepository;

/**
 * 脱敏模块引导器
 * <p>
 * 负责脱敏模块的生命周期管理与初始化。
 */
public class MaskBootstrap {

    private static final MaskBootstrap INSTANCE = new MaskBootstrap();

    public static MaskBootstrap global() {
        return INSTANCE;
    }

    /**
     * 启动脱敏模块
     *
     * @param configManager 配置管理器
     */
    public void start(ConfigManager configManager) {
        MaskRuleRepository.getInstance().init(configManager);
    }

    /**
     * 停止脱敏模块并清理规则
     */
    public void stop() {
        MaskRuleRepository.getInstance().reset();
    }
}
