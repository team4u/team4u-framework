package com.team4u.framework.log.pipeline;

import com.team4u.framework.log.core.LogEvent;
import com.team4u.framework.log.pipeline.interceptor.MdcEnrichInterceptor;
import com.team4u.framework.log.pipeline.interceptor.RateLimitInterceptor;
import com.team4u.framework.policy.core.OrderedPolicyChain;
import com.team4u.framework.policy.engine.PolicyPipeline;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * Logging interceptor chain with engine-owned defaults, SPI additions, and explicit custom additions.
 */
public class LogInterceptorManager {

    private final OrderedPolicyChain<LogEvent, LogInterceptor> chain;
    private final PolicyPipeline<LogEvent, LogInterceptor> pipeline;
    private final Object coreMonitor = new Object();
    private final Set<LogInterceptor> coreInterceptors =
            Collections.newSetFromMap(new IdentityHashMap<LogInterceptor, Boolean>());
    public LogInterceptorManager() {
        this.chain = new OrderedPolicyChain<>(LogInterceptor.class);
        MdcEnrichInterceptor mdcInterceptor = MdcEnrichInterceptor.create();
        RateLimitInterceptor rateInterceptor = RateLimitInterceptor.create();
        registerCore(mdcInterceptor);
        registerCore(rateInterceptor);

        List<LogInterceptor> spiInterceptors = new ArrayList<>();
        try {
            for (LogInterceptor interceptor : ServiceLoader.load(LogInterceptor.class)) {
                spiInterceptors.add(interceptor);
            }
        } catch (RuntimeException error) {
            throw new IllegalStateException("Unable to load LogInterceptor SPI instances", error);
        }
        for (LogInterceptor interceptor : spiInterceptors) {
            registerCore(interceptor);
        }

        this.pipeline = new PolicyPipeline<>(chain);
    }

    public void register(LogInterceptor interceptor) {
        if (interceptor == null) {
            return;
        }
        synchronized (coreMonitor) {
            if (containsIdentity(chain.getPolicies(), interceptor)) {
                return;
            }
            chain.register(interceptor);
        }
    }

    public void unregister(LogInterceptor interceptor) {
        synchronized (coreMonitor) {
            chain.unregisterIf(existing -> existing == interceptor);
            coreInterceptors.remove(interceptor);
        }
    }

    public List<LogInterceptor> getInterceptors() {
        return chain.getPolicies();
    }

    public <T extends LogInterceptor> T getInterceptor(Class<T> interceptorType) {
        for (LogInterceptor interceptor : chain.getPolicies()) {
            if (interceptorType.isInstance(interceptor)) {
                return interceptorType.cast(interceptor);
            }
        }
        return null;
    }

    public boolean execute(LogEvent event) {
        return pipeline.executeChain(event, LogInterceptor::handle);
    }

    public boolean shouldProcessDisabledLevel(LogEvent event) {
        return chain.getPolicies().stream()
                .filter(interceptor -> interceptor.supports(event))
                .anyMatch(interceptor -> interceptor.shouldBypassLevelPrecheck(event));
    }

    /**
     * Legacy cleanup path: stops every interceptor and aggregates failures.
     */
    public void reset() {
        RuntimeException firstError = null;
        for (LogInterceptor interceptor : chain.getPolicies()) {
            try {
                interceptor.stop();
            } catch (RuntimeException error) {
                firstError = addSuppressed(firstError, error);
            }
        }
        if (firstError != null) {
            throw firstError;
        }
    }

    /**
     * Engine reset path: only defaults and SPI interceptors are engine-owned runtime state.
     */
    public void resetCore() {
        RuntimeException firstError = null;
        synchronized (coreMonitor) {
            for (LogInterceptor interceptor : chain.getPolicies()) {
                if (!coreInterceptors.contains(interceptor)) {
                    continue;
                }
                try {
                    interceptor.stop();
                } catch (RuntimeException error) {
                    firstError = addSuppressed(firstError, error);
                }
            }
        }
        if (firstError != null) {
            throw firstError;
        }
    }

    private void registerCore(LogInterceptor interceptor) {
        if (interceptor == null || containsIdentity(chain.getPolicies(), interceptor)) {
            return;
        }
        chain.register(interceptor);
        coreInterceptors.add(interceptor);
    }

    private static boolean containsIdentity(List<LogInterceptor> interceptors, LogInterceptor interceptor) {
        for (LogInterceptor current : interceptors) {
            if (current == interceptor) {
                return true;
            }
        }
        return false;
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
}
