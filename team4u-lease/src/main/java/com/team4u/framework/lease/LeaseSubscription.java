package com.team4u.framework.lease;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/**
 * Worker 声明的订阅能力。
 */
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class LeaseSubscription {

    @Builder.Default
    private final String namespace = "default";
    private final String queue;
}
