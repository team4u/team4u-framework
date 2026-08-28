package com.team4u.framework.log;

import com.team4u.framework.config.core.ConfigManager;
import com.team4u.framework.criterion.Criteria;
import com.team4u.framework.log.appender.Slf4jLogAppender;
import com.team4u.framework.log.config.FinOpsConfigRepository;
import com.team4u.framework.log.core.LogEngine;
import com.team4u.framework.log.jackson.JacksonLogSerializer;
import com.team4u.framework.log.pipeline.interceptor.RateLimitInterceptor;
import com.team4u.framework.log.pipeline.interceptor.TargetedDyeingInterceptor;
import com.team4u.framework.log.proxy.ProxyRuleRepository;
import com.team4u.framework.mask.config.MaskBootstrap;
import lombok.Builder;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Explicit assembly and lifecycle owner for log governance.
 */
public final class LogBootstrap {

    private static final Logger log = LoggerFactory.getLogger(LogBootstrap.class);
    private static final LogBootstrap INSTANCE = new LogBootstrap();
    private static final String COMPONENTS = "mask,dyeing,finops,proxy,engine";

    private final Object lifecycleMonitor = new Object();
    private final AtomicReference<State> state = new AtomicReference<>(State.NEW);

    private volatile Options activeOptions;
    private volatile LogEngine installedEngine;
    private volatile LogEngine previousEngine;
    private volatile RateLimitInterceptor installedRate;

    private LogBootstrap() {
    }

    public static void start() {
        INSTANCE.startInternal(Options.defaults());
    }

    public static void start(Options options) {
        INSTANCE.startInternal(Options.resolve(options));
    }

    public static void reconfigure(Options options) {
        INSTANCE.reconfigureInternal(Options.resolve(options));
    }

    public static void stop() {
        INSTANCE.stopInternal();
    }

    public static boolean isStarted() {
        return INSTANCE.state.get() == State.STARTED;
    }

    public static State getState() {
        return INSTANCE.state.get();
    }

    static LogEngine getInstalledEngineForTest() {
        return INSTANCE.installedEngine;
    }

    static LogEngine getPreviousEngineForTest() {
        return INSTANCE.previousEngine;
    }

    private void startInternal(Options options) {
        synchronized (lifecycleMonitor) {
            State current = state.get();
            if (current == State.STARTED || current == State.RECONFIGURING || current == State.STARTING) {
                logLifecycle("start", "duplicate_start", options, 0L, null, false);
                return;
            }

            transitionTo(State.STARTING);
            long startedAt = System.nanoTime();
            LogEngine assembledEngine = null;
            try {
                assembledEngine = assembleEngine(options);
                installedEngine = assembledEngine;
                installedRate = assembledEngine.getInterceptorManager()
                        .getInterceptor(RateLimitInterceptor.class);
                previousEngine = LogEngine.install(assembledEngine);
                applyOptions(options);
                activeOptions = options;
                transitionTo(State.STARTED);
                logLifecycle("start", "initial", options, startedAt, null, false);
            } catch (RuntimeException e) {
                RuntimeException cleanupError = cleanupFailedStart(assembledEngine);
                if (cleanupError != null) {
                    e.addSuppressed(cleanupError);
                }
                activeOptions = null;
                transitionTo(State.FAILED);
                logLifecycle("start", "initial", options, startedAt, e, true);
                throw e;
            }
        }
    }

    private RuntimeException cleanupFailedStart(LogEngine assembledEngine) {
        RuntimeException firstError = null;
        try {
            if (assembledEngine != null) {
                try {
                    if (LogEngine.getInstance() == assembledEngine) {
                        LogEngine.restore(assembledEngine, previousEngine);
                    }
                } catch (RuntimeException error) {
                    firstError = error;
                }
                try {
                    assembledEngine.reset();
                } catch (RuntimeException error) {
                    firstError = addSuppressed(firstError, error);
                }
            }

            firstError = stopRepositories(firstError);
            return firstError;
        } finally {
            installedEngine = null;
            previousEngine = null;
            installedRate = null;
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
                    activeOptions = null;
                    reconfigureError.addSuppressed(rollbackError);
                    RuntimeException cleanupError = stopComponents();
                    if (cleanupError != null) {
                        reconfigureError.addSuppressed(cleanupError);
                    }
                    transitionTo(State.FAILED);
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
                RuntimeException error = stopComponents();
                if (error != null) {
                    throw error;
                }
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

    private LogEngine assembleEngine(Options options) {
        JacksonLogSerializer serializer = new JacksonLogSerializer();
        return LogEngine.builder()
                .serializer(serializer)
                .interceptor(TargetedDyeingInterceptor.getInstance())
                .build();
    }

    private void applyOptions(Options options) {
        TargetedDyeingInterceptor.getInstance().setCriteria(options.getCriteria());
        RateLimitInterceptor rate = installedRate;
        if (rate == null) {
            rate = installedEngine.getInterceptorManager().getInterceptor(RateLimitInterceptor.class);
            installedRate = rate;
        }
        rate.setErrorLimitPerSecond(() ->
                FinOpsConfigRepository.getInstance().get().getErrorLimitPerSecond());

        MaskBootstrap.global().start(options.getConfigManager());
        TargetedDyeingInterceptor.getInstance().init(options.getConfigManager());
        FinOpsConfigRepository.getInstance().init(options.getConfigManager());
        ProxyRuleRepository.getInstance().init(options.getConfigManager());
        installedEngine.getSerializer().reset();
    }

    private RuntimeException stopComponents() {
        RuntimeException firstError;
        try {
            firstError = restoreEngine();
        } finally {
            installedEngine = null;
            previousEngine = null;
        }

        try {
            firstError = stopRepositories(firstError);
        } finally {
            RateLimitInterceptor rate = installedRate;
            if (rate != null) {
                try {
                    rate.resetErrorLimitPerSecond();
                    rate.stop();
                } catch (RuntimeException error) {
                    firstError = addSuppressed(firstError, error);
                } finally {
                    installedRate = null;
                }
            }
        }
        return firstError;
    }

    private RuntimeException stopRepositories(RuntimeException firstError) {
        firstError = stopRepository(new ProxyRepositoryStop(), firstError);
        firstError = stopRepository(new FinOpsRepositoryStop(), firstError);
        firstError = stopRepository(new TargetedRepositoryStop(), firstError);
        firstError = stopRepository(new MaskRepositoryStop(), firstError);
        return firstError;
    }

    private interface RepositoryStop {
        void stop();
    }

    private static final class ProxyRepositoryStop implements RepositoryStop {
        public void stop() {
            ProxyRuleRepository.getInstance().stop();
        }
    }

    private static final class FinOpsRepositoryStop implements RepositoryStop {
        public void stop() {
            FinOpsConfigRepository.getInstance().stop();
        }
    }

    private static final class TargetedRepositoryStop implements RepositoryStop {
        public void stop() {
            TargetedDyeingInterceptor.getInstance().stop();
        }
    }

    private static final class MaskRepositoryStop implements RepositoryStop {
        public void stop() {
            MaskBootstrap.global().stop();
        }
    }

    private RuntimeException stopRepository(RepositoryStop repository, RuntimeException firstError) {
        RuntimeException error = firstError;
        try {
            repository.stop();
        } catch (RuntimeException e) {
            error = addSuppressed(error, e);
        }
        return error;
    }

    private RuntimeException restoreEngine() {
        LogEngine engine = installedEngine;
        LogEngine previous = previousEngine;
        if (engine == null || previous == null) {
            return null;
        }

        RuntimeException firstError = null;
        try {
            if (LogEngine.restore(engine, previous)) {
                engine.reset();
            } else {
                // Ownership moved on; reset without touching the newer global appender.
                engine.setAppender(new Slf4jLogAppender());
                engine.getInterceptorManager().resetCore();
                engine.getSerializer().reset();
            }
        } catch (RuntimeException error) {
            firstError = error;
        } finally {
            installedEngine = null;
            previousEngine = null;
        }
        return firstError;
    }

    private RuntimeException addSuppressed(RuntimeException firstError, RuntimeException error) {
        if (firstError == null) {
            return error;
        }
        if (firstError != error) {
            firstError.addSuppressed(error);
        }
        return firstError;
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

    @Getter
    @Builder
    public static final class Options {

        private static final Options DEFAULTS = Options.builder().build();

        @Builder.Default
        private final ConfigManager configManager = ConfigManager.global();

        @Builder.Default
        private final Criteria criteria = Criteria.global();

        public static Options defaults() {
            return DEFAULTS;
        }

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
