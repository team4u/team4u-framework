package com.team4u.framework.log;

import com.team4u.framework.config.core.ConfigManager;
import com.team4u.framework.criterion.Criteria;
import com.team4u.framework.log.config.FinOpsConfigRepository;
import com.team4u.framework.log.core.LogEngine;
import com.team4u.framework.log.pipeline.interceptor.TargetedDyeingInterceptor;
import com.team4u.framework.log.proxy.ProxyRuleRepository;
import com.team4u.framework.mask.MaskBootstrap;
import lombok.Builder;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 日志模块引导类
 * <p>
 * 统一管理日志模块生命周期，并显式区分启动、重配与停止语义。
 */
public final class LogBootstrap {

    private static final Logger log = LoggerFactory.getLogger(LogBootstrap.class);
    private static final LogBootstrap INSTANCE = new LogBootstrap();
    private static final String COMPONENTS = "mask,dyeing,finops,proxy,engine";

    private final Object lifecycleMonitor = new Object();
    private final AtomicReference<State> state = new AtomicReference<>(State.NEW);

    private volatile Options activeOptions;

    private LogBootstrap() {
    }

    /**
     * 启动日志模块
     * <p>
     * 使用默认的引导配置初始化日志系统各组件，支持防重复启动。
     */
    public static void start() {
        INSTANCE.startInternal(Options.defaults());
    }

    /**
     * 启动日志模块（指定配置）
     *
     * @param options 自定义的日志引导配置项
     */
    public static void start(Options options) {
        INSTANCE.startInternal(Options.resolve(options));
    }

    /**
     * 动态重新配置日志模块
     * <p>
     * 热替换底层配置管理器或匹配条件。若重配过程发生异常，将安全回滚至上一稳定状态。
     *
     * @param options 最新的日志引导配置项
     */
    public static void reconfigure(Options options) {
        INSTANCE.reconfigureInternal(Options.resolve(options));
    }

    /**
     * 停止日志模块运行
     * <p>
     * 优雅卸载所有注册的日志拦截器和配置监视器，释放相关资源。
     */
    public static void stop() {
        INSTANCE.stopInternal();
    }

    /**
     * 检查模块是否已成功启动
     *
     * @return 处于 STARTED 状态时返回 true，否则返回 false
     */
    public static boolean isStarted() {
        return INSTANCE.state.get() == State.STARTED;
    }

    /**
     * 获取当前系统引导状态
     *
     * @return 返回生命周期状态枚举
     */
    public static State getState() {
        return INSTANCE.state.get();
    }

    private void startInternal(Options options) {
        synchronized (lifecycleMonitor) {
            State current = state.get();
            if (current == State.STARTED) {
                logLifecycle("start", "duplicate_start", options, 0L, null, false);
                return;
            }

            transitionTo(State.STARTING);
            long startedAt = System.nanoTime();
            try {
                applyOptions(options);
                activeOptions = options;
                transitionTo(State.STARTED);
                logLifecycle("start", "initial", options, startedAt, null, false);
            } catch (RuntimeException e) {
                rollbackToStopped();
                activeOptions = null;
                transitionTo(State.FAILED);
                logLifecycle("start", "initial", options, startedAt, e, true);
                throw e;
            }
        }
    }

    private void reconfigureInternal(Options options) {
        synchronized (lifecycleMonitor) {
            if (state.get() != State.STARTED) {
                throw new IllegalStateException("LogBootstrap can only reconfigure from STARTED state.");
            }

            Options previous = activeOptions != null ? activeOptions : Options.defaults();
            transitionTo(State.RECONFIGURING);
            long startedAt = System.nanoTime();
            try {
                applyOptions(options);
                activeOptions = options;
                transitionTo(State.STARTED);
                logLifecycle("reconfigure", "reconfigure", options, startedAt, null, false);
            } catch (RuntimeException reconfigureError) {
                boolean rollbackFailed = false;
                try {
                    applyOptions(previous);
                    activeOptions = previous;
                    transitionTo(State.STARTED);
                } catch (RuntimeException rollbackError) {
                    rollbackFailed = true;
                    rollbackToStopped();
                    activeOptions = null;
                    transitionTo(State.FAILED);
                    reconfigureError.addSuppressed(rollbackError);
                }
                logLifecycle("reconfigure", "reconfigure", options, startedAt, reconfigureError, true);
                if (!rollbackFailed) {
                    log.info("LogBootstrap|reconfigure|rollback|success|state={}", state.get());
                }
                throw reconfigureError;
            }
        }
    }

    private void stopInternal() {
        synchronized (lifecycleMonitor) {
            State current = state.get();
            if (current == State.NEW || current == State.STOPPED) {
                return;
            }

            transitionTo(State.STOPPING);
            long startedAt = System.nanoTime();
            try {
                stopComponents();
                activeOptions = null;
                transitionTo(State.STOPPED);
                logLifecycle("stop", "stop", null, startedAt, null, false);
            } catch (RuntimeException e) {
                transitionTo(State.FAILED);
                logLifecycle("stop", "stop", activeOptions, startedAt, e, false);
                throw e;
            }
        }
    }

    private void applyOptions(Options options) {
        bindExecutionDependencies(options);
        initConfigDrivenComponents(options);
        warmupEngine();
    }

    private void bindExecutionDependencies(Options options) {
        TargetedDyeingInterceptor.getInstance().setCriteria(options.getCriteria());
    }

    private void initConfigDrivenComponents(Options options) {
        MaskBootstrap.global().start(options.getConfigManager());
        TargetedDyeingInterceptor.getInstance().init(options.getConfigManager());
        FinOpsConfigRepository.getInstance().init(options.getConfigManager());
        ProxyRuleRepository.getInstance().init(options.getConfigManager());
    }

    private void warmupEngine() {
        LogEngine.getInstance();
    }

    private void rollbackToStopped() {
        try {
            stopComponents();
        } catch (RuntimeException rollbackError) {
            log.warn("LogBootstrap|rollback|error|msg={}", rollbackError.getMessage(), rollbackError);
        }
    }

    private void stopComponents() {
        RuntimeException firstError = null;

        try {
            ProxyRuleRepository.getInstance().stop();
        } catch (RuntimeException e) {
            firstError = e;
        }

        try {
            FinOpsConfigRepository.getInstance().stop();
        } catch (RuntimeException e) {
            if (firstError == null) {
                firstError = e;
            } else {
                firstError.addSuppressed(e);
            }
        }

        try {
            TargetedDyeingInterceptor.getInstance().stop();
        } catch (RuntimeException e) {
            if (firstError == null) {
                firstError = e;
            } else {
                firstError.addSuppressed(e);
            }
        }

        try {
            MaskBootstrap.global().stop();
        } catch (RuntimeException e) {
            if (firstError == null) {
                firstError = e;
            } else {
                firstError.addSuppressed(e);
            }
        }

        if (firstError != null) {
            throw firstError;
        }
    }

    private void transitionTo(State next) {
        state.set(next);
    }

    private void logLifecycle(
            String action,
            String mode,
            Options options,
            long startedAtNanos,
            Exception error,
            boolean rollback) {
        String configManagerType = options == null
                ? "n/a"
                : options.getConfigManager() == ConfigManager.global() ? "default" : "custom";
        String criteriaType = options == null
                ? "n/a"
                : options.getCriteria() == Criteria.global() ? "default" : "custom";
        long costMs = startedAtNanos > 0 ? (System.nanoTime() - startedAtNanos) / 1_000_000 : 0L;

        if (error == null) {
            log.info(
                    "LogBootstrap|{}|success|mode={}|state={}|configManager={}|criteria={}|components={}|costMs={}",
                    action, mode, state.get(), configManagerType, criteriaType, COMPONENTS, costMs);
            return;
        }

        log.error(
                "LogBootstrap|{}|error|mode={}|state={}|configManager={}|criteria={}|components={}|costMs={}|rollback={}|msg={}",
                action, mode, state.get(), configManagerType, criteriaType, COMPONENTS, costMs, rollback,
                error.getMessage(), error);
    }

    public enum State {
        NEW,
        STARTING,
        STARTED,
        RECONFIGURING,
        STOPPING,
        STOPPED,
        FAILED
    }

    /**
     * 日志引导配置
     * <p>
     * 使用不可变快照承载启动和重配依赖，避免共享单例上的隐式重配。
     */
    @Getter
    @Builder
    public static final class Options {

        /**
         * 默认的日志引导配置实例
         */
        private static final Options DEFAULTS = Options.builder().build();

        /**
         * 配置管理器，决定日志配置获取的数据源及监听机制
         */
        @Builder.Default
        private final ConfigManager configManager = ConfigManager.global();

        /**
         * 匹配条件引擎，用于解析动态规则中的过滤表达式
         */
        @Builder.Default
        private final Criteria criteria = Criteria.global();

        /**
         * 获取默认配置实例
         *
         * @return 默认的引导配置
         */
        public static Options defaults() {
            return DEFAULTS;
        }

        /**
         * 解析并补全引导配置
         * <p>
         * 确保传入对象的必填字段存在默认值，如果对象内部有空缺，将使用默认配置填补。
         *
         * @param options 待解析的引导配置对象
         * @return 完整且合法的引导配置实例
         */
        static Options resolve(Options options) {
            if (options == null) {
                return DEFAULTS;
            }
            if (options.getConfigManager() != null && options.getCriteria() != null) {
                return options;
            }

            OptionsBuilder builder = Options.builder();

            if (options.getConfigManager() != null) {
                builder.configManager(options.getConfigManager());
            }

            if (options.getCriteria() != null) {
                builder.criteria(options.getCriteria());
            }
            return builder.build();
        }
    }
}