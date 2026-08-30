package com.team4u.it;

import com.team4u.framework.config.core.ConfigManager;
import com.team4u.framework.config.core.spi.InMemoryConfigSource;
import com.team4u.framework.kv.NamedKvStoreRegistry;
import com.team4u.framework.kv.memory.InMemoryKvStore;
import com.team4u.framework.ratelimiter.api.RateLimitReason;
import com.team4u.framework.ratelimiter.api.RateLimitResult;
import com.team4u.framework.ratelimiter.api.RateLimiters;

import java.util.Collections;

/**
 * External consumer proof for the split ratelimiter core artifact:
 * BOM import + the single direct Team4u dependency team4u-ratelimiter-core
 * runs real rate-limit decisions with a named in-memory store. Rule JSON is
 * parsed by the application-provided MiniJsonPolicy (ServiceLoader), proving
 * the documented explicit-provider contract: core itself never passes
 * Jackson or team4u-serializer-jackson at runtime.
 */
public class RateLimiterCoreMain {

    public static void main(String[] args) {
        // 1. One JSON rule: fixed window, threshold 1, per-user key template.
        InMemoryConfigSource source = new InMemoryConfigSource("consumer", 0);
        source.put("team4u.ratelimiter.order.create",
                "[{\"id\":\"per-user\",\"algorithm\":\"fixed-window\","
                        + "\"windowMillis\":60000,\"threshold\":1,\"key\":\"${userId}\"}]");
        ConfigManager configManager = ConfigManager.builder()
                .addSource(source).addWatcher(source).build();

        // 2. Register the in-memory store by name (NamedKvStoreRegistry lives in
        //    team4u-kv-space) and use it as the engine's default store.
        InMemoryKvStore store = new InMemoryKvStore();
        NamedKvStoreRegistry.global().register("main", store);
        RateLimiters.init(configManager, NamedKvStoreRegistry.global().get("main"));

        try {
            // 3. Real decisions: first call passes, the second is denied at the threshold.
            if (!RateLimiters.tryAcquire("order.create",
                    Collections.singletonMap("userId", "u1"))) {
                throw new IllegalStateException("First acquire should pass");
            }
            RateLimitResult denied = RateLimiters.acquire("order.create",
                    Collections.singletonMap("userId", "u1"));
            if (denied.isAllowed() || denied.getReason() != RateLimitReason.THRESHOLD) {
                throw new IllegalStateException("Second acquire should hit THRESHOLD: " + denied);
            }
            // Different user key -> independent quota.
            if (!RateLimiters.tryAcquire("order.create",
                    Collections.singletonMap("userId", "u2"))) {
                throw new IllegalStateException("Independent key should pass");
            }
            // No-rule point -> allowed with NO_RULE.
            RateLimitResult noRule = RateLimiters.acquire("no.rule", null);
            if (!noRule.isAllowed() || noRule.getReason() != RateLimitReason.NO_RULE) {
                throw new IllegalStateException("No-rule point should be NO_RULE: " + noRule);
            }
            System.out.println("ratelimiter-core consumer ok: threshold denied, key isolated");
        } finally {
            RateLimiters.destroy();
            NamedKvStoreRegistry.global().unregister("main");
        }
    }
}
