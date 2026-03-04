package com.team4u.log;

import cn.hutool.log.Log;
import com.team4u.framework.config.core.ConfigManager;
import com.team4u.framework.criterion.Criteria;
import com.team4u.log.config.FinOpsConfigRepository;
import com.team4u.log.core.LogEngine;
import com.team4u.log.pipeline.interceptor.TargetedDyeingInterceptor;
import com.team4u.log.proxy.ProxyRuleRepository;
import com.team4u.mask.MaskBootstrap;

/**
 * 日志模块引导类
 * <p>
 * 提供统一入口初始化动态脱敏、定向染色与配置中心集成。
 * 支持链式设置可选依赖，未设置时使用默认全局实例。
 */
public class LogBootstrap {

    private static final Log log = Log.get();

    private static final LogBootstrap INSTANCE = new LogBootstrap();

    private volatile ConfigManager configManager = ConfigManager.global();

    private volatile Criteria criteria = Criteria.global();

    private LogBootstrap() {
    }

    public static LogBootstrap global() {
        return INSTANCE;
    }

    public LogBootstrap configManager(ConfigManager configManager) {
        this.configManager = configManager == null ? ConfigManager.global() : configManager;
        return this;
    }

    public LogBootstrap criteria(Criteria criteria) {
        this.criteria = criteria == null ? Criteria.global() : criteria;
        return this;
    }

    public void start() {
        // 1. 设置 Criteria 依赖
        TargetedDyeingInterceptor.getInstance().setCriteria(criteria);

        // 2. 将 ConfigManager 下发，让各个领域组件完成自我配置加载
        MaskBootstrap.global().start(configManager);
        TargetedDyeingInterceptor.getInstance().init(configManager);
        FinOpsConfigRepository.getInstance().init(configManager);
        ProxyRuleRepository.getInstance().init(configManager);

        // 3. 初始化核心引擎
        LogEngine.getInstance();

        log.info("LogBootstrap|start|success|Domain-Driven Config enabled.");
    }
}
