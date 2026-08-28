package com.team4u.framework.log.pipeline;

import com.team4u.framework.log.core.LogEvent;
import com.team4u.framework.log.pipeline.interceptor.MdcEnrichInterceptor;
import com.team4u.framework.log.pipeline.interceptor.RateLimitInterceptor;
import com.team4u.framework.policy.core.OrderedPolicyChain;
import com.team4u.framework.policy.engine.PolicyPipeline;
import com.team4u.framework.policy.util.PolicyScanner;

import java.util.List;
/**
 * Logging interceptor chain with core defaults and explicit custom additions.
 */
public class LogInterceptorManager {

    private final OrderedPolicyChain<LogEvent, LogInterceptor> chain;
    private final PolicyPipeline<LogEvent, LogInterceptor> pipeline;

    public LogInterceptorManager() {
        this.chain = new OrderedPolicyChain<>(LogInterceptor.class);
        this.chain.register(MdcEnrichInterceptor.getInstance());
        this.chain.register(RateLimitInterceptor.getInstance());
        PolicyScanner.registerFromServiceLoader(chain);
        this.pipeline = new PolicyPipeline<>(chain);
    }

    public void register(LogInterceptor interceptor) {
        if (interceptor == null || chain.getPolicies().contains(interceptor)) {
            return;
        }
        chain.register(interceptor);
    }
    public void unregister(LogInterceptor interceptor) {
        chain.unregister(interceptor);
    }

    public List<LogInterceptor> getInterceptors() {
        return chain.getPolicies();
    }

    public boolean execute(LogEvent event) {
        return pipeline.executeChain(event, LogInterceptor::handle);
    }

    public boolean shouldProcessDisabledLevel(LogEvent event) {
        return chain.getPolicies().stream()
                .filter(interceptor -> interceptor.supports(event))
                .anyMatch(interceptor -> interceptor.shouldBypassLevelPrecheck(event));
    }

    public void reset() {
        chain.getPolicies().forEach(LogInterceptor::stop);
    }
}
