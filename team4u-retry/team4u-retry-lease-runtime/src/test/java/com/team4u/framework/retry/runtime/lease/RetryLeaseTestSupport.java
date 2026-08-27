package com.team4u.framework.retry.runtime.lease;

import com.team4u.framework.retry.api.RecoverySpec;
import com.team4u.framework.retry.api.RetryPolicy;
import com.team4u.framework.retry.common.backoff.Backoffs;
import com.team4u.framework.retry.managed.model.RetryRequest;
import com.team4u.framework.retry.managed.model.RetryState;
import com.team4u.framework.retry.managed.model.RetryStatus;
import com.team4u.framework.retry.managed.store.record.RetryRecord;

import java.time.Instant;

final class RetryLeaseTestSupport {

    private RetryLeaseTestSupport() {
    }

    static RetryRecord retryRecord(String taskType, String idempotencyKey, RetryPolicy policy) {
        return RetryRecord.builder()
                .request(RetryRequest.builder()
                        .taskType(taskType)
                        .idempotencyKey(idempotencyKey)
                        .recovery(RecoverySpec.of(taskType, "payload-" + idempotencyKey))
                        .policy(policy)
                        .createdAt(Instant.now())
                        .build())
                .state(RetryState.builder()
                        .attempts(0)
                        .status(RetryStatus.ACCEPTED)
                        .nextRunAt(Instant.now())
                        .build())
                .build();
    }

    static RetryPolicy retryPolicy(int maxRetries, long delayMillis) {
        return RetryPolicy.builder()
                .maxRetries(maxRetries)
                .foregroundMaxRetries(0)
                .backoff(Backoffs.fixed(delayMillis))
                .build();
    }
}
