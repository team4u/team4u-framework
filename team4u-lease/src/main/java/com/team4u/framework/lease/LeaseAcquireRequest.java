package com.team4u.framework.lease;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Singular;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Worker 获取租约的请求。
 */
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class LeaseAcquireRequest {

    private final String workerId;
    private final long leaseMillis;
    private final long waitTimeoutMillis;
    @Singular
    private final Set<LeaseSubscription> subscriptions;

    public Set<LeaseSubscription> getSubscriptions() {
        if (subscriptions == null) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(new LinkedHashSet<LeaseSubscription>(subscriptions));
    }
}
