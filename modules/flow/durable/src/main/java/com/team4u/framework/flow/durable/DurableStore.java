package com.team4u.framework.flow.durable;

import java.util.Optional;

/**
 * Side-effect-free load plus revision compare-and-set storage contract.
 * An expected revision of -1 means create-if-absent.
 */
public interface DurableStore {
    Optional<DurableSnapshot> load(String executionId);

    boolean compareAndSet(String executionId, long expectedRevision,
                          DurableSnapshot update);
}
