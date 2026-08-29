package com.team4u.framework.singleflight.config;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

/**
 * Singleflight rule loaded from configuration key {@code team4u.singleflight.{point}}.
 * <p>
 * Instances are mutable during JSON deserialization only. After validation the
 * engine treats them as immutable runtime configuration.
 * </p>
 *
 * @author jay.wu
 */
@Data
public class SingleFlightRule {

    /**
     * Rule id. Defaults to the point name; must not contain ':'.
     */
    private String id;

    /**
     * Enables this rule. When false the loader is invoked directly.
     */
    private boolean enabled = true;

    /**
     * Named store registered in {@code SingleFlightStores}; blank means the engine default store.
     */
    private String store;

    /**
     * Key template such as {@code ${productId}}. The point is always part of the final key.
     */
    private String key;

    /**
     * Criterion applied to the parameter-name map. Blank means never skip.
     */
    private String skipWhen;

    /**
     * Criterion applied to the loader return value. Blank means cacheable.
     */
    private String cacheWhen;

    /**
     * Behavior on lock contention.
     */
    private ContentionPolicy contention = ContentionPolicy.WAIT;

    /**
     * Native fallback JSON. Missing means disabled; explicit JSON null means a null return value.
     */
    private JsonNode fallback;

    private long lockLeaseMillis = 30_000;
    private long waitTimeoutMillis = 10_000;
    private long pollIntervalMillis = 100;

    private boolean cacheEnabled = true;
    private long cacheTtlMillis;

    private long uncacheableTtlMillis = 5_000;
    private long failureTtlMillis = 5_000;

    private RuleMissingPolicy onRuleMissing = RuleMissingPolicy.PASS_THROUGH;
    private InvalidKeyPolicy onInvalidKey = InvalidKeyPolicy.ERROR;

    /**
     * Optional explicit store-failure policy. Its default depends on contention policy.
     */
    private StoreFailurePolicy onStoreFailure;

    /**
     * Rendered key length threshold that triggers SHA-256 digesting.
     */
    private int digestThreshold = 128;
}
