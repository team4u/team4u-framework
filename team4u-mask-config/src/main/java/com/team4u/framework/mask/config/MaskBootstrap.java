package com.team4u.framework.mask.config;

import com.team4u.framework.config.core.ConfigManager;

/**
 * Lifecycle bootstrap for config-driven mask rules.
 */
public class MaskBootstrap {

    private static final MaskBootstrap INSTANCE = new MaskBootstrap();

    private MaskBootstrap() {
    }

    public static MaskBootstrap global() {
        return INSTANCE;
    }

    public void start(ConfigManager configManager) {
        MaskRuleRepository.getInstance().init(configManager);
    }

    public void stop() {
        MaskRuleRepository.getInstance().reset();
    }
}
